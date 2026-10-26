@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package za.co.drkflm.afterhours.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.network.httpHeaders
import com.google.android.gms.location.LocationServices
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import za.co.drkflm.afterhours.AppViewModel
import za.co.drkflm.afterhours.data.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File

private fun String?.visibleProfileValue():String? = this?.trim()?.takeIf {
    it.isNotEmpty() && it.lowercase() !in setOf("unavailable","hidden","unknown","not set","n/a","null","off")
}

@Composable private fun Header(title:String,subtitle:String?=null,action:(@Composable () -> Unit)?=null){Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White);subtitle?.let{Text(it,color=Muted)}};action?.invoke()}}
@Composable private fun authenticatedImageModel(url:String?):ImageRequest { val context=LocalContext.current; return ImageRequest.Builder(context).data(ApiClient.mediaUrl(url)).httpHeaders(ApiClient.imageHeaders()).build() }
@Composable private fun Avatar(url:String?,modifier:Modifier=Modifier.size(62.dp)){AsyncImage(authenticatedImageModel(url),null,modifier.clip(CircleShape).background(Raised),contentScale=ContentScale.Crop)}

@Composable fun DiscoverScreen(vm:AppViewModel) {
    var items by remember { mutableStateOf<JsonArray>(JsonArray(emptyList())) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var filters by remember { mutableStateOf(false) }
    var radius by remember { mutableFloatStateOf(25f) }
    var online by remember { mutableStateOf(false) }
    fun load() { vm.runTask { loading=true; items=ApiClient.request("discovery?radius=${radius.toInt()}&onlineOnly=$online").jsonObject.array("items"); loading=false } }
    LaunchedEffect(Unit) { load() }
    selected?.let { ProfileScreen(vm,it.str("id")){selected=null}; return }
    Column(Modifier.fillMaxSize()) {
        Header("The Grid","Nearby members") { IconButton({filters=true}){Icon(Icons.Default.Tune,"Filters")} }
        PullToRefreshBox(isRefreshing=loading,onRefresh={load()},modifier=Modifier.weight(1f)) {
            LazyVerticalGridCompat(items) { profile -> ProfileTile(profile,{selected=profile},{vm.runTask{val next=!profile.bool("liked");ApiClient.request("likes/${profile.str("id")}",if(next)HttpMethod.Post else HttpMethod.Delete);load()}}) }
        }
    }
    if(filters) ModalBottomSheet(onDismissRequest={filters=false}) {
        Column(Modifier.padding(24.dp)) {
            Text("Discovery filters",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("Radius ${radius.toInt()} km",color=Lavender)
            Slider(radius,{radius=it},valueRange=1f..100f)
            Row(verticalAlignment=Alignment.CenterVertically){Switch(online,{online=it});Spacer(Modifier.width(10.dp));Text("Online now")}
            Spacer(Modifier.height(16.dp));NeonButton("Apply filters"){filters=false;load()};Spacer(Modifier.height(20.dp))
        }
    }
}
@Composable private fun LazyVerticalGridCompat(items:JsonArray,item:@Composable (JsonObject) -> Unit){LazyColumn(contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp)){items(items.chunked(2)){row->Row(Modifier.fillMaxWidth()){row.forEach{Box(Modifier.weight(1f).padding(6.dp)){item(it.jsonObject)}};if(row.size==1)Spacer(Modifier.weight(1f))}}}}
@Composable private fun ProfileTile(p:JsonObject,open:()->Unit,like:()->Unit){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).clickable(onClick=open)){Box{AsyncImage(authenticatedImageModel(p.strOrNull("photo")),null,Modifier.fillMaxWidth().aspectRatio(.78f).background(Raised),contentScale=ContentScale.Crop);IconButton(like,Modifier.align(Alignment.TopEnd)){Icon(if(p.bool("liked"))Icons.Default.Favorite else Icons.Default.FavoriteBorder,"Like",tint=HotPink)}};Column(Modifier.padding(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("${p.str("displayName")}${p["age"]?.jsonPrimitive?.contentOrNull?.let{", $it"}.orEmpty()}",fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color.White);if(p.bool("verified"))Icon(Icons.Default.Verified,null,tint=Lavender,modifier=Modifier.size(17.dp))};val metadata=listOf(p.strOrNull("distance").visibleProfileValue(),p.strOrNull("city").visibleProfileValue()).filterNotNull();if(metadata.isNotEmpty())Text(metadata.joinToString(" · "),color=Muted,style=MaterialTheme.typography.bodySmall)}}}

@Composable fun ProfileScreen(vm:AppViewModel,id:String,back:()->Unit){
    BackHandler(onBack=back)
    var p by remember{mutableStateOf<JsonObject?>(null)}
    var safety by remember{mutableStateOf(false)}
    LaunchedEffect(id){vm.runTask{p=ApiClient.request("profiles/$id").jsonObject}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
        Header(p?.objectOrNull("profile")?.str("displayName")?:"Profile"){IconButton(back){Icon(Icons.Default.ArrowBack,"Back",tint=Color.White)}}
        p?.let{data->
            val profile=data.objectOrNull("profile")?:return@let
            val photos=data.array("photos")
            if(photos.isNotEmpty())AsyncImage(authenticatedImageModel(photos.first().jsonObject.str("url")),null,Modifier.fillMaxWidth().height(430.dp),contentScale=ContentScale.Crop)
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                Text("${profile.str("displayName")}${data["age"]?.jsonPrimitive?.contentOrNull?.let{", $it"}.orEmpty()}",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White)
                val location=data.objectOrNull("location")
                val metadata=listOf(location?.strOrNull("distance").visibleProfileValue(),location?.strOrNull("city").visibleProfileValue(),profile.strOrNull("presenceLabel").visibleProfileValue()).filterNotNull()
                if(metadata.isNotEmpty())Text(metadata.joinToString(" · "),color=Lavender)
                profile.strOrNull("bio").visibleProfileValue()?.let{Text(it,Modifier.glassCard(),color=Color.White)}
                listOf("Interests" to "interests","Intentions" to "intentions","Kinks" to "kinks","Tags" to "tags").forEach{(title,key)->
                    val values=profile.array(key).mapNotNull{it.jsonPrimitive.contentOrNull.visibleProfileValue()}
                    if(values.isNotEmpty())Column(Modifier.fillMaxWidth().glassCard()){Text(title,fontWeight=FontWeight.Bold,color=Color.White);Text(values.joinToString(" · "),color=Lavender)}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    Button({vm.runTask{ApiClient.request("likes/$id",HttpMethod.Post)}},Modifier.weight(1f)){Icon(Icons.Default.Favorite,null);Text(" Like")}
                    Button({vm.runTask{val conversation=ApiClient.request("conversations",HttpMethod.Post,obj("userId" to id)).jsonObject;vm.pendingConversation.value=conversation.str("id")}},Modifier.weight(1f)){Icon(Icons.Default.Chat,null);Text(" Message")}
                }
                TextButton({safety=true},Modifier.align(Alignment.CenterHorizontally)){Text("Block or report",color=Color(0xFFFFB4AB))}
            }
        }
    }
    if(safety)AlertDialog({safety=false},title={Text("Safety actions")},text={Text("You can report this profile for review, block it so you no longer see each other, or do both.")},confirmButton={TextButton({vm.runTask{ApiClient.request("reports",HttpMethod.Post,obj("userId" to id,"reason" to "OTHER","details" to "Reported from Android app"));ApiClient.request("blocks",HttpMethod.Post,obj("userId" to id))};safety=false}){Text("Report & block")}},dismissButton={TextButton({safety=false}){Text("Cancel")}})
}

@Composable fun LikesScreen(vm:AppViewModel){var tab by remember{mutableIntStateOf(0)};var items by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))};var selected by remember{mutableStateOf<String?>(null)};suspend fun load(){val data=ApiClient.request(if(tab==0)"likes/received" else "likes");items=if(tab==0)data.jsonObject.array("items")else data.jsonArray};LaunchedEffect(tab){vm.runTask{load()}};selected?.let{ProfileScreen(vm,it){selected=null};return};Column{Header("Likes","Connections waiting in the wings");TabRow(tab){Tab(tab==0,{tab=0},text={Text("Liked you")});Tab(tab==1,{tab=1},text={Text("You liked")})};LazyColumn{if(items.isEmpty())item{Text("No likes here yet.",color=Muted,modifier=Modifier.padding(24.dp))};items(items.size){i->val p=items[i].jsonObject;ListItem(headlineContent={Text(p.str("displayName"),fontWeight=FontWeight.Bold)},supportingContent={Text(p.strOrNull("bio")?:p.str("city"),maxLines=2)},leadingContent={Avatar(p.strOrNull("photo"))},modifier=Modifier.clickable{selected=p.str("id")})}}}
}

private fun conversationPreview(c:JsonObject):String { if(c.str("status")=="REQUESTED")return "Message request";val m=c["lastMessage"]?.jsonObject?:return "Start chatting";val body=m.str("body");if(body.startsWith("GALLERY_SHARE:"))return "Private gallery shared";if(body.startsWith("LOCATION:"))return "📍 Shared location";return when(m.str("kind").uppercase()){ "IMAGE","GIF"->"Image received";"VIDEO"->"Video received";"GALLERY"->"Private gallery shared";else->body } }
@Composable fun InboxScreen(vm:AppViewModel){var tab by remember{mutableIntStateOf(0)};var items by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))};var chat by remember{mutableStateOf<JsonObject?>(null)};suspend fun load(){items=ApiClient.request(if(tab==0)"conversations" else "group-chats").jsonArray;vm.refreshUnread()};LaunchedEffect(tab){vm.runTask{load()}};LaunchedEffect(vm.pendingConversation.value){vm.pendingConversation.value?.let{id->vm.runTask{val all=ApiClient.request("conversations").jsonArray;chat=all.firstOrNull{it.jsonObject.str("id")==id}?.jsonObject}}};chat?.let{if(tab==0)DirectChat(vm,it){chat=null;vm.runTask{load()}}else GroupChatScreen(vm,it){chat=null};return};Column{Header("Chats","Messages, requests and groups");TabRow(tab){Tab(tab==0,{tab=0},text={Text("Direct")});Tab(tab==1,{tab=1},text={Text("Groups")})};LazyColumn{if(items.isEmpty())item{Text("Nothing here yet.",color=Muted,modifier=Modifier.padding(24.dp))};items(items.size){i->val c=items[i].jsonObject;val other=c["other"]?.jsonObject;ListItem(headlineContent={Text(if(tab==0)other?.str("displayName").orEmpty()else c.str("title"),fontWeight=FontWeight.Bold,color=Color.White)},supportingContent={Text(if(tab==0)conversationPreview(c)else "${c.int("pendingCount")} pending · ${c.int("unreadCount")} unread",maxLines=1,color=Muted)},leadingContent={if(tab==0)Avatar(other?.strOrNull("photo"))else Icon(Icons.Default.Groups,null,Modifier.size(52.dp),tint=Lavender)},trailingContent={if(c.int("unreadCount")>0)Badge{Text(c.int("unreadCount").toString())}},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.clickable{chat=c})}}}}

@Composable private fun DirectChat(vm:AppViewModel,c:JsonObject,back:()->Unit){
    BackHandler(onBack=back)
    val context=LocalContext.current
    val id=c.str("id")
    val other=c.objectOrNull("other")
    var messages by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))}
    var text by remember{mutableStateOf("")}
    var attach by remember{mutableStateOf(false)}
    var cameraFile by remember{mutableStateOf<File?>(null)}
    var chooseGallery by remember{mutableStateOf(false)}
    val listState=rememberLazyListState()
    suspend fun load(){messages=ApiClient.request("conversations/$id/messages").jsonArray;val unread=messages.map{it.jsonObject}.filter{it.str("senderId")!=vm.user.value?.str("id")}.map{it.str("id")}.filter{it.isNotBlank()};if(unread.isNotEmpty())runCatching{ApiClient.request("conversations/$id/read",HttpMethod.Post,buildJsonObject{put("messageIds",buildJsonArray{unread.forEach{add(it)}})})}}
    fun upload(file:File){vm.runTask{ApiClient.upload("conversations/$id/images",file);load()}}
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{vm.runTask{uploadFileFromUri(context,it,"conversations/$id/images");load()}}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){saved->if(saved)cameraFile?.let(::upload)}
    fun openCamera(){val dir=File(context.cacheDir,"camera").apply{mkdirs()};val file=File(dir,"chat-${System.currentTimeMillis()}.jpg");cameraFile=file;camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.files",file))}
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)openCamera()else vm.error.value="Camera permission is required to take a photo."}
    LaunchedEffect(Unit){vm.runTask{load()}}
    LaunchedEffect(messages.size){if(messages.isNotEmpty())listState.scrollToItem(messages.lastIndex)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(back){Icon(Icons.Default.ArrowBack,"Back",tint=Color.White)}
            Avatar(other?.strOrNull("photo"),Modifier.size(46.dp))
            Spacer(Modifier.width(12.dp))
            Column{Text(other?.str("displayName")?:"Chat",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color.White);other?.strOrNull("presenceLabel").visibleProfileValue()?.let{Text(it,color=Muted,style=MaterialTheme.typography.bodySmall)}}
        }
        LazyColumn(Modifier.weight(1f),state=listState,contentPadding=PaddingValues(12.dp)){items(messages.size){i->MessageBubble(messages[i].jsonObject,vm.user.value?.str("id"))}}
        Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){IconButton({attach=true}){Icon(Icons.Default.AddCircle,"Attachments",tint=Lavender)};OutlinedTextField(text,{text=it},Modifier.weight(1f),placeholder={Text("Message",color=Muted)});IconButton({val body=text.trim();if(body.isNotEmpty()){text="";vm.runTask{ApiClient.request("conversations/$id/messages",HttpMethod.Post,obj("body" to body,"kind" to "TEXT"));load()}}}){Icon(Icons.Default.Send,"Send",tint=HotPink)}}
    }
    if(attach)AttachmentSheet(dismiss={attach=false},vm=vm,send={body->vm.runTask{ApiClient.request("conversations/$id/messages",HttpMethod.Post,obj("body" to body,"kind" to "TEXT"));load()}},camera={attach=false;if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)openCamera()else cameraPermission.launch(Manifest.permission.CAMERA)},photo={attach=false;photoPicker.launch("image/*")},privateGallery={attach=false;chooseGallery=true})
    if(chooseGallery)PrivateGalleryPicker(vm,{chooseGallery=false}){gallery->chooseGallery=false;val userId=other?.strOrNull("userId")?:other?.strOrNull("id");if(userId==null)vm.error.value="This member could not be identified." else vm.runTask{ApiClient.request("private-galleries/${gallery.str("id")}/access",HttpMethod.Post,obj("userId" to userId));load()}}
}
@Composable private fun MessageBubble(m:JsonObject,me:String?){val mine=m.str("senderId")==me;Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start){Column(Modifier.widthIn(max=300.dp).background(if(mine)Electric.copy(alpha=.5f)else Raised,RoundedCornerShape(18.dp)).padding(12.dp)){val body=m.str("body");val kind=m.str("kind").uppercase();when{m.bool("viewOnce")->Text(if(mine)"View-once media sent" else "Tap to open view-once media",color=Lavender,fontWeight=FontWeight.SemiBold);body.startsWith("LOCATION:")-> {Text("📍 Shared location",fontWeight=FontWeight.Bold,color=Color.White);Text("Tap to open in Maps",color=Muted)};kind=="IMAGE"||kind=="GIF"->AsyncImage(authenticatedImageModel(body),"Chat image",Modifier.fillMaxWidth().heightIn(min=120.dp,max=320.dp).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Fit);kind=="VIDEO"->Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.PlayCircle,null,tint=Lavender);Spacer(Modifier.width(8.dp));Text("Video received",color=Color.White)};kind=="GALLERY"||body.startsWith("GALLERY_SHARE:")->Text("Private gallery shared",color=Color.White,fontWeight=FontWeight.SemiBold);else->Text(body,color=Color.White)}}}}

@Composable private fun AttachmentSheet(dismiss:()->Unit,vm:AppViewModel,send:(String)->Unit,camera:()->Unit,photo:()->Unit,privateGallery:()->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    fun shareLocation(){scope.launch{runCatching{currentLocation(context)}.onSuccess{send("LOCATION:${it.first},${it.second}");dismiss()}.onFailure{vm.error.value=it.message?:"Location could not be shared."}}}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->if(result.values.any{it})shareLocation()else vm.error.value="Location permission is required to share your location."}
    ModalBottomSheet(dismiss){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Share",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        ListItem(headlineContent={Text("Share current location")},leadingContent={Icon(Icons.Default.LocationOn,null)},modifier=Modifier.clickable{if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)shareLocation()else request.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))})
        ListItem(headlineContent={Text("Camera")},supportingContent={Text("Take a new photo")},leadingContent={Icon(Icons.Default.PhotoCamera,null)},modifier=Modifier.clickable(onClick=camera))
        ListItem(headlineContent={Text("Photo Gallery")},supportingContent={Text("Choose an existing photo")},leadingContent={Icon(Icons.Default.PhotoLibrary,null)},modifier=Modifier.clickable(onClick=photo))
        ListItem(headlineContent={Text("Private Gallery")},leadingContent={Icon(Icons.Default.Collections,null)},modifier=Modifier.clickable(onClick=privateGallery))
        Spacer(Modifier.height(14.dp))
    }}
}
private suspend fun uploadFileFromUri(context:Context,uri:Uri,path:String){val name=context.contentResolver.getType(uri)?.substringAfter('/')?.takeIf{it.length<=5}?:"jpg";val file=File(context.cacheDir,"chat-upload-${System.currentTimeMillis()}.$name");context.contentResolver.openInputStream(uri)?.use{input->file.outputStream().use{output->input.copyTo(output)}}?:error("The selected photo could not be read.");try{ApiClient.upload(path,file)}finally{file.delete()}}
@Composable private fun PrivateGalleryPicker(vm:AppViewModel,dismiss:()->Unit,select:(JsonObject)->Unit){var galleries by remember{mutableStateOf<JsonArray?>(null)};LaunchedEffect(Unit){vm.runTask{val response=ApiClient.request("private-galleries");galleries=if(response is JsonArray)response else response.jsonObject.array("items")}};AlertDialog(dismiss,title={Text("Share a private gallery")},text={LazyColumn(Modifier.heightIn(max=420.dp)){val items=galleries;if(items==null)item{CircularProgressIndicator()}else if(items.isEmpty())item{Text("You do not have a private gallery to share yet.",color=Muted)}else items(items.size){index->val gallery=items[index].jsonObject;ListItem(headlineContent={Text(gallery.strOrNull("title")?:gallery.strOrNull("name")?:"Private gallery")},supportingContent={Text("${gallery.array("photos").size} photos")},leadingContent={Icon(Icons.Default.Collections,null,tint=Lavender)},modifier=Modifier.clickable{select(gallery)})}}},confirmButton={TextButton(dismiss){Text("Cancel")}})}
private suspend fun currentLocation(context:android.content.Context):Pair<Double,Double> = suspendCancellableCoroutine{cont->val client=LocationServices.getFusedLocationProviderClient(context);try{client.lastLocation.addOnSuccessListener{loc->if(loc!=null)cont.resume(loc.latitude to loc.longitude)else cont.resumeWithException(Exception("Current location is unavailable. Try again outdoors."))}.addOnFailureListener{cont.resumeWithException(it)}}catch(e:SecurityException){cont.resumeWithException(e)}}

@Composable private fun GroupChatScreen(vm:AppViewModel,g:JsonObject,back:()->Unit){BackHandler(onBack=back);val id=g.str("id");var messages by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))};var text by remember{mutableStateOf("")};val listState=rememberLazyListState();suspend fun load(){messages=ApiClient.request("group-chats/$id/messages").jsonArray};LaunchedEffect(Unit){vm.runTask{load()}};LaunchedEffect(messages.size){if(messages.isNotEmpty())listState.scrollToItem(messages.lastIndex)};Column(Modifier.fillMaxSize()){Header(g.str("title")){IconButton(back){Icon(Icons.Default.ArrowBack,"Back")}};if(g.str("status")=="INVITED")Row(Modifier.padding(12.dp).glassCard()){Button({vm.runTask{ApiClient.request("group-chats/$id/respond",HttpMethod.Post,obj("accept" to true));load()}},Modifier.weight(1f)){Text("Accept")};Spacer(Modifier.width(8.dp));OutlinedButton({vm.runTask{ApiClient.request("group-chats/$id/respond",HttpMethod.Post,obj("accept" to false));back()}},Modifier.weight(1f)){Text("Decline")}};LazyColumn(Modifier.weight(1f),state=listState,contentPadding=PaddingValues(12.dp)){items(messages.size){i->val m=messages[i].jsonObject;Text(m.str("senderName"),color=Lavender,style=MaterialTheme.typography.labelSmall);MessageBubble(m,vm.user.value?.str("id"))}};Row(Modifier.padding(8.dp)){OutlinedTextField(text,{text=it},Modifier.weight(1f));IconButton({val body=text.trim();if(body.isNotBlank()){text="";vm.runTask{ApiClient.request("group-chats/$id/messages",HttpMethod.Post,obj("body" to body));load()}}}){Icon(Icons.Default.Send,"Send")}}}}

@Composable fun PremiumScreen(vm:AppViewModel){var sub by remember{mutableStateOf<JsonObject?>(null)};LaunchedEffect(Unit){vm.runTask{sub=ApiClient.request("subscription").jsonObject}};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){Header("Premium","Own the night");Column(Modifier.padding(18.dp).glassCard()){Icon(Icons.Default.Diamond,null,tint=Lavender,modifier=Modifier.size(54.dp));Text((sub?.str("tier")?:vm.user.value?.str("subscription")?:"FREE").uppercase(),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Wider discovery, advanced filters and read receipts.",color=Muted,modifier=Modifier.padding(vertical=10.dp));listOf("See more people across a larger radius","Use advanced discovery filters","Know when your messages are read","Support an independent, adults-only community").forEach{Row(Modifier.padding(vertical=6.dp)){Icon(Icons.Default.CheckCircle,null,tint=HotPink);Spacer(Modifier.width(8.dp));Text(it)}};Spacer(Modifier.height(16.dp));NeonButton(if(sub?.str("tier")=="PREMIUM")"Manage membership" else "Upgrade to Premium"){vm.runTask{ApiClient.request("subscription/demo-upgrade",HttpMethod.Post);sub=ApiClient.request("subscription").jsonObject;vm.refresh()}}};Text("Payments in this build use the server’s demo subscription flow. Google Play Billing must be configured before public sale.",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(24.dp))}}

@Composable fun MeScreen(vm:AppViewModel,lockEnabled:Boolean,setLock:(Boolean)->Unit){val u=vm.user.value;val profile=u?.objectOrNull("profile");val location=u?.objectOrNull("location");var editor by remember{mutableStateOf(false)};var galleries by remember{mutableStateOf(false)};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){Header("Me","Your profile and privacy");Column(Modifier.padding(horizontal=18.dp).glassCard()){Row(verticalAlignment=Alignment.CenterVertically){Avatar(u?.array("photos")?.firstOrNull()?.let{it as? JsonObject}?.strOrNull("url"),Modifier.size(82.dp));Spacer(Modifier.width(14.dp));Column{Text(profile?.str("displayName")?.takeIf{it.isNotBlank()}?:"AFTRHRS member",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color.White);Text(u?.str("email").orEmpty(),color=Muted);Text(u?.str("verification").orEmpty(),color=Lavender)}}};SettingsItem(Icons.Default.Edit,"Edit profile","Photos, bio, identity and interests"){editor=true};SettingsItem(Icons.Default.LocationOn,"Share current area",location?.str("city")?.takeIf{it.isNotBlank()}?:"Off"){vm.error.value="Use location sharing from a chat or enable it in privacy settings."};SettingsItem(Icons.Default.Collections,"Shared galleries","Manage private photo access"){galleries=true};SettingsItem(Icons.Default.Lock,"App Lock",if(lockEnabled)"On" else "Off"){setLock(!lockEnabled)};SettingsItem(Icons.Default.Link,"Linked partners","Manage profile links"){};SettingsItem(Icons.Default.PrivacyTip,"Privacy settings","Visibility, requests, receipts and discovery"){};SettingsItem(Icons.Default.Description,"End User Licence Agreement","afterhours.drkflm.co.za/eula"){ };SettingsItem(Icons.Default.Logout,"Sign out",null){vm.logout()};Spacer(Modifier.height(24.dp))};if(editor)ProfileEditor(vm){editor=false};if(galleries)SharedGalleries(vm){galleries=false}}
@Composable private fun SettingsItem(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,subtitle:String?,action:()->Unit){ListItem(headlineContent={Text(title,fontWeight=FontWeight.SemiBold)},supportingContent={subtitle?.let{Text(it)}},leadingContent={Icon(icon,null,tint=Lavender)},trailingContent={Icon(Icons.Default.ChevronRight,null)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.padding(horizontal=10.dp).clickable(onClick=action))}

@Composable private fun ProfileEditor(vm:AppViewModel,dismiss:()->Unit){val profile=vm.user.value?.objectOrNull("profile");var name by remember{mutableStateOf(profile?.str("displayName").orEmpty())};var bio by remember{mutableStateOf(profile?.str("bio").orEmpty())};var identity by remember{mutableStateOf(profile?.str("identity").orEmpty())};var interests by remember{mutableStateOf(profile?.array("interests")?.joinToString(", "){it.jsonPrimitive.content}.orEmpty())};AlertDialog(dismiss,title={Text("Edit profile")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text("Display name")});OutlinedTextField(bio,{bio=it},label={Text("Bio")},minLines=3);OutlinedTextField(identity,{identity=it},label={Text("Identity")});OutlinedTextField(interests,{interests=it},label={Text("Interests, comma separated")})}},confirmButton={TextButton({vm.runTask{ApiClient.request("profiles/me",HttpMethod.Patch,buildJsonObject{put("displayName",name);put("bio",bio);put("identity",identity);put("interests",buildJsonArray{interests.split(',').map{it.trim()}.filter{it.isNotBlank()}.forEach{add(it)}})});vm.refresh()};dismiss()}){Text("Save")}},dismissButton={TextButton(dismiss){Text("Cancel")}})}

@Composable private fun SharedGalleries(vm:AppViewModel,dismiss:()->Unit){var data by remember{mutableStateOf<JsonObject?>(null)};LaunchedEffect(Unit){vm.runTask{data=ApiClient.request("private-galleries/sharing").jsonObject}};AlertDialog(dismiss,title={Text("Shared galleries")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())){val owned=data?.array("sharedWith")?:JsonArray(emptyList());val received=data?.array("sharedWithMe")?:JsonArray(emptyList());Text("Shared by you",fontWeight=FontWeight.Bold,color=Lavender);if(owned.isEmpty())Text("Nothing shared yet",color=Muted);owned.forEach{g->val o=g.jsonObject;ListItem(headlineContent={Text(o.str("title"))},supportingContent={Text("${o.array("photos").size} photos · ${o.array("recipients").size} recipients")})};Spacer(Modifier.height(12.dp));Text("Shared with you",fontWeight=FontWeight.Bold,color=Lavender);if(received.isEmpty())Text("Nothing shared with you yet",color=Muted);received.forEach{g->val o=g.jsonObject;ListItem(headlineContent={Text(o.str("title"))},supportingContent={Text("From ${o["owner"]?.jsonObject?.str("name").orEmpty()}")})}}},confirmButton={TextButton(dismiss){Text("Done")}})}
