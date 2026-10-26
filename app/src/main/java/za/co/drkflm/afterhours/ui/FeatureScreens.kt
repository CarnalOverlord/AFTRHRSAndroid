@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package za.co.drkflm.afterhours.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.graphics.Brush
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
private fun positionGlyph(position:String)=when(position){"Top"->"↑";"Top / Vers"->"↗";"Vers"->"↕";"Bottom / Vers"->"↘";"Bottom"->"↓";"Side"->"↔";else->""}
@Composable private fun GridChip(text:String,symbol:String?=null){Row(Modifier.border(1.dp,Electric.copy(alpha=.8f),RoundedCornerShape(50)).background(Electric.copy(alpha=.13f),RoundedCornerShape(50)).padding(horizontal=7.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){symbol?.takeIf{it.isNotBlank()}?.let{Text(it,color=Lavender,style=MaterialTheme.typography.labelSmall)};Text(text,color=Lavender,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold,maxLines=1)}}
@Composable private fun ProfileTile(p:JsonObject,open:()->Unit,like:()->Unit){
    val shape=RoundedCornerShape(20.dp)
    val position=p.strOrNull("position").visibleProfileValue()?.takeUnless{it=="Prefer not to say"}
    val status=p.strOrNull("circumcisionStatus")?.takeIf{it=="Cut"||it=="Uncut"}
    val pronouns=p.strOrNull("pronouns").visibleProfileValue()?.takeUnless{it=="Prefer not to say"}
    val tags=p.array("tags").mapNotNull{it.jsonPrimitive.contentOrNull.visibleProfileValue()}.take(3)
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Raised).border(if(p.bool("chatted"))2.5.dp else 1.dp,if(p.bool("chatted"))Electric else Color.White.copy(alpha=.12f),shape).clickable(onClick=open)){
        AsyncImage(authenticatedImageModel(p.strOrNull("photo")),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent,.46f to Color.Black.copy(alpha=.12f),1f to Color.Black.copy(alpha=.96f))))
        Column(Modifier.matchParentSize().padding(9.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){
                Text(if(p.bool("online"))"Online" else "Offline",color=Color.White,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold,modifier=Modifier.background(Color.Black.copy(alpha=.58f),RoundedCornerShape(50)).padding(horizontal=8.dp,vertical=5.dp))
                Spacer(Modifier.weight(1f))
                IconButton(like,Modifier.size(38.dp).background(Color.Black.copy(alpha=.58f),CircleShape)){Icon(if(p.bool("liked"))Icons.Default.Favorite else Icons.Default.FavoriteBorder,"Like",tint=if(p.bool("liked"))HotPink else Color.White,modifier=Modifier.size(21.dp))}
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                Text("${p.str("displayName")}${p["age"]?.jsonPrimitive?.contentOrNull?.let{", $it"}.orEmpty()}",fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color.White,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f,false))
                if(p.bool("verified"))Icon(Icons.Default.Verified,"Verified",tint=Lavender,modifier=Modifier.size(17.dp))
            }
            if(position!=null||status!=null)Row(Modifier.padding(top=5.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){position?.let{GridChip(it,positionGlyph(it))};status?.let{GridChip(it,if(it=="Cut")"✂" else "○")}}
            pronouns?.let{Text(it,color=Muted,style=MaterialTheme.typography.labelSmall,maxLines=1,modifier=Modifier.padding(top=4.dp))}
            if(tags.isNotEmpty())Row(Modifier.padding(top=3.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){tags.forEach{Text("#$it",color=Lavender,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold,maxLines=1)}}
        }
        val unread=p.int("unreadMessages")
        if(unread>0)Text(if(unread>99)"99+"else unread.toString(),color=Color.White,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.BottomEnd).padding(7.dp).background(Neon,RoundedCornerShape(50)).padding(horizontal=7.dp,vertical=5.dp))
    }
}

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
                    ElectricButton({vm.runTask{ApiClient.request("likes/$id",HttpMethod.Post)}},Modifier.weight(1f)){Icon(Icons.Default.Favorite,null);Text(" Like")}
                    ElectricButton({vm.runTask{val conversation=ApiClient.request("conversations",HttpMethod.Post,obj("userId" to id)).jsonObject;vm.pendingConversation.value=conversation.str("id")}},Modifier.weight(1f)){Icon(Icons.Default.Chat,null);Text(" Message")}
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
private suspend fun uploadFileFromUri(context:Context,uri:Uri,path:String,field:String="image"){val name=context.contentResolver.getType(uri)?.substringAfter('/')?.takeIf{it.length<=5}?:"jpg";val file=File(context.cacheDir,"upload-${System.currentTimeMillis()}.$name");context.contentResolver.openInputStream(uri)?.use{input->file.outputStream().use{output->input.copyTo(output)}}?:error("The selected photo could not be read.");try{ApiClient.upload(path,file,field)}finally{file.delete()}}
@Composable private fun PrivateGalleryPicker(vm:AppViewModel,dismiss:()->Unit,select:(JsonObject)->Unit){var galleries by remember{mutableStateOf<JsonArray?>(null)};LaunchedEffect(Unit){vm.runTask{val response=ApiClient.request("private-galleries");galleries=if(response is JsonArray)response else response.jsonObject.array("items")}};AlertDialog(dismiss,title={Text("Share a private gallery")},text={LazyColumn(Modifier.heightIn(max=420.dp)){val items=galleries;if(items==null)item{CircularProgressIndicator()}else if(items.isEmpty())item{Text("You do not have a private gallery to share yet.",color=Muted)}else items(items.size){index->val gallery=items[index].jsonObject;ListItem(headlineContent={Text(gallery.strOrNull("title")?:gallery.strOrNull("name")?:"Private gallery")},supportingContent={Text("${gallery.array("photos").size} photos")},leadingContent={Icon(Icons.Default.Collections,null,tint=Lavender)},modifier=Modifier.clickable{select(gallery)})}}},confirmButton={TextButton(dismiss){Text("Cancel")}})}
private suspend fun currentLocation(context:android.content.Context):Pair<Double,Double> = suspendCancellableCoroutine{cont->val client=LocationServices.getFusedLocationProviderClient(context);try{client.lastLocation.addOnSuccessListener{loc->if(loc!=null)cont.resume(loc.latitude to loc.longitude)else cont.resumeWithException(Exception("Current location is unavailable. Try again outdoors."))}.addOnFailureListener{cont.resumeWithException(it)}}catch(e:SecurityException){cont.resumeWithException(e)}}

@Composable private fun GroupChatScreen(vm:AppViewModel,g:JsonObject,back:()->Unit){BackHandler(onBack=back);val id=g.str("id");var messages by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))};var text by remember{mutableStateOf("")};val listState=rememberLazyListState();suspend fun load(){messages=ApiClient.request("group-chats/$id/messages").jsonArray};LaunchedEffect(Unit){vm.runTask{load()}};LaunchedEffect(messages.size){if(messages.isNotEmpty())listState.scrollToItem(messages.lastIndex)};Column(Modifier.fillMaxSize()){Header(g.str("title")){IconButton(back){Icon(Icons.Default.ArrowBack,"Back")}};if(g.str("status")=="INVITED")Row(Modifier.padding(12.dp).glassCard()){ElectricButton({vm.runTask{ApiClient.request("group-chats/$id/respond",HttpMethod.Post,obj("accept" to true));load()}},Modifier.weight(1f)){Text("Accept")};Spacer(Modifier.width(8.dp));OutlinedButton({vm.runTask{ApiClient.request("group-chats/$id/respond",HttpMethod.Post,obj("accept" to false));back()}},Modifier.weight(1f)){Text("Decline")}};LazyColumn(Modifier.weight(1f),state=listState,contentPadding=PaddingValues(12.dp)){items(messages.size){i->val m=messages[i].jsonObject;Text(m.str("senderName"),color=Lavender,style=MaterialTheme.typography.labelSmall);MessageBubble(m,vm.user.value?.str("id"))}};Row(Modifier.padding(8.dp)){OutlinedTextField(text,{text=it},Modifier.weight(1f));IconButton({val body=text.trim();if(body.isNotBlank()){text="";vm.runTask{ApiClient.request("group-chats/$id/messages",HttpMethod.Post,obj("body" to body));load()}}}){Icon(Icons.Default.Send,"Send")}}}}

@Composable fun PremiumScreen(vm:AppViewModel){var sub by remember{mutableStateOf<JsonObject?>(null)};LaunchedEffect(Unit){vm.runTask{sub=ApiClient.request("subscription").jsonObject}};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){Header("Premium","Own the night");Column(Modifier.padding(18.dp).glassCard()){Icon(Icons.Default.Diamond,null,tint=Lavender,modifier=Modifier.size(54.dp));Text((sub?.str("tier")?:vm.user.value?.str("subscription")?:"FREE").uppercase(),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Wider discovery, advanced filters and read receipts.",color=Muted,modifier=Modifier.padding(vertical=10.dp));listOf("See more people across a larger radius","Use advanced discovery filters","Know when your messages are read","Support an independent, adults-only community").forEach{Row(Modifier.padding(vertical=6.dp)){Icon(Icons.Default.CheckCircle,null,tint=HotPink);Spacer(Modifier.width(8.dp));Text(it)}};Spacer(Modifier.height(16.dp));NeonButton(if(sub?.str("tier")=="PREMIUM")"Manage membership" else "Upgrade to Premium"){vm.runTask{ApiClient.request("subscription/demo-upgrade",HttpMethod.Post);sub=ApiClient.request("subscription").jsonObject;vm.refresh()}}};Text("Payments in this build use the server’s demo subscription flow. Google Play Billing must be configured before public sale.",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(24.dp))}}

@Composable fun MeScreen(vm:AppViewModel,lockEnabled:Boolean,setLock:(Boolean)->Unit){val u=vm.user.value;val profile=u?.objectOrNull("profile");var editor by remember{mutableStateOf(false)};var galleries by remember{mutableStateOf(false)};var privacy by remember{mutableStateOf(false)};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){Header("Me","Your profile and privacy");Column(Modifier.padding(horizontal=18.dp).glassCard()){Row(verticalAlignment=Alignment.CenterVertically){Avatar(u?.array("photos")?.firstOrNull()?.let{it as? JsonObject}?.strOrNull("url"),Modifier.size(82.dp));Spacer(Modifier.width(14.dp));Column{Text(profile?.str("displayName")?.takeIf{it.isNotBlank()}?:"AFTRHRS member",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color.White);Text(u?.str("email").orEmpty(),color=Muted);Text(u?.str("verification").orEmpty(),color=Lavender)}}};SettingsItem(Icons.Default.Edit,"Edit profile","Photos, bio, identity and interests"){editor=true};SettingsItem(Icons.Default.Collections,"Shared galleries","Manage private photo access"){galleries=true};SettingsItem(Icons.Default.Lock,"App Lock",if(lockEnabled)"On" else "Off"){setLock(!lockEnabled)};SettingsItem(Icons.Default.Link,"Linked partners","Manage profile links"){};SettingsItem(Icons.Default.PrivacyTip,"Privacy settings","Visibility, requests, receipts and discovery"){privacy=true};SettingsItem(Icons.Default.Description,"End User Licence Agreement","afterhours.drkflm.co.za/eula"){ };SettingsItem(Icons.Default.Logout,"Sign out",null){vm.logout()};Spacer(Modifier.height(24.dp))};if(editor)ProfileEditor(vm){editor=false};if(galleries)SharedGalleries(vm){galleries=false};if(privacy)PrivacySettings(vm){privacy=false}}
@Composable private fun SettingsItem(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,subtitle:String?,action:()->Unit){ListItem(headlineContent={Text(title,fontWeight=FontWeight.SemiBold)},supportingContent={subtitle?.let{Text(it)}},leadingContent={Icon(icon,null,tint=Lavender)},trailingContent={Icon(Icons.Default.ChevronRight,null)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.padding(horizontal=10.dp).clickable(onClick=action))}

@Composable private fun PrivacyToggle(title:String,checked:Boolean,enabled:Boolean=true,onChecked:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Text(title,color=if(enabled)Color.White else Muted,modifier=Modifier.weight(1f));Switch(checked,onChecked,enabled=enabled)}}
@Composable private fun PrivacySection(title:String,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().glassCard(),verticalArrangement=Arrangement.spacedBy(2.dp)){Text(title,color=Lavender,fontWeight=FontWeight.Bold,modifier=Modifier.padding(bottom=3.dp));content()}}
@Composable private fun PrivacySettings(vm:AppViewModel,dismiss:()->Unit){
    val user=vm.user.value
    val preference=user?.objectOrNull("preference")
    val isPremium=user?.str("subscription")=="PREMIUM"
    fun initial(key:String,default:Boolean)=preference?.get(key)?.jsonPrimitive?.booleanOrNull?:default
    var showAge by remember{mutableStateOf(initial("showAge",true))};var showPosition by remember{mutableStateOf(initial("showPosition",true))};var hideOnline by remember{mutableStateOf(initial("hideOnline",false))};var hideDistance by remember{mutableStateOf(initial("hideDistance",false))};var pauseDiscovery by remember{mutableStateOf(initial("pauseDiscovery",false))};var browsePrivately by remember{mutableStateOf(isPremium&&initial("browsePrivately",false))};var markChatted by remember{mutableStateOf(initial("markChattedOnGrid",true))};var showUnread by remember{mutableStateOf(initial("showGridMessageCount",true))};var allowRequests by remember{mutableStateOf(initial("allowRequests",true))};var readReceipts by remember{mutableStateOf(initial("allowReadReceipts",true))};var notifications by remember{mutableStateOf(initial("notifications",true))};var preview by remember{mutableStateOf(preference?.strOrNull("notificationPreview")?:"PRIVATE")};var saving by remember{mutableStateOf(false)}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=Panel){Column(Modifier.fillMaxWidth().heightIn(max=760.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("Privacy & safety",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White)
        PrivacySection("Visibility"){PrivacyToggle("Show my age",showAge){showAge=it};PrivacyToggle("Show my position",showPosition){showPosition=it};PrivacyToggle("Hide online status",hideOnline){hideOnline=it};PrivacyToggle("Hide my distance",hideDistance){hideDistance=it};PrivacyToggle("Pause discovery",pauseDiscovery){pauseDiscovery=it};PrivacyToggle("Browse privately",browsePrivately,isPremium){browsePrivately=it};if(!isPremium)Text("Private browsing requires Premium.",color=Muted,style=MaterialTheme.typography.bodySmall)}
        PrivacySection("The Grid"){PrivacyToggle("Mark profiles I've chatted with",markChatted){markChatted=it};PrivacyToggle("Show unread message counts",showUnread){showUnread=it};Text("These indicators are only visible to you.",color=Muted,style=MaterialTheme.typography.bodySmall)}
        PrivacySection("Connections"){PrivacyToggle("Allow message requests",allowRequests){allowRequests=it};PrivacyToggle("Share read receipts",readReceipts){readReceipts=it}}
        PrivacySection("Notifications"){PrivacyToggle("Allow notifications",notifications){notifications=it;if(it&&Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)};Text("Notification previews",color=if(notifications)Color.White else Muted,modifier=Modifier.padding(top=5.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(preview=="PRIVATE",{preview="PRIVATE"},label={Text("Private")},enabled=notifications);FilterChip(preview=="FULL",{preview="FULL"},label={Text("Full")},enabled=notifications)}}
        Text("Your exact coordinates are never shown to other members. Private browsing hides profile visits and requires Premium. Android notification permission can also be changed in system settings.",color=Muted,style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(dismiss,Modifier.weight(1f)){Text("Cancel")};ElectricButton(onClick={saving=true;vm.runTask{try{ApiClient.request("preferences",HttpMethod.Patch,buildJsonObject{put("showAge",showAge);put("showPosition",showPosition);put("hideOnline",hideOnline);put("hideDistance",hideDistance);put("pauseDiscovery",pauseDiscovery);put("browsePrivately",isPremium&&browsePrivately);put("markChattedOnGrid",markChatted);put("showGridMessageCount",showUnread);put("allowRequests",allowRequests);put("allowReadReceipts",readReceipts);put("notifications",notifications);put("notificationPreview",preview)});vm.refresh();dismiss()}finally{saving=false}}},modifier=Modifier.weight(1f),enabled=!saving){if(saving)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else Text("Save")}}
        Spacer(Modifier.height(28.dp))
    }}
}

@Composable private fun SelectField(label:String,value:String,options:List<String>,display:(String)->String={if(it.isBlank())"Not specified"else it},select:(String)->Unit){var expanded by remember{mutableStateOf(false)};ExposedDropdownMenuBox(expanded,{expanded=it}){OutlinedTextField(value=display(value),onValueChange={},readOnly=true,label={Text(label)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},modifier=Modifier.fillMaxWidth().menuAnchor());ExposedDropdownMenu(expanded,{expanded=false}){options.forEach{option->DropdownMenuItem({Text(display(option))},{select(option);expanded=false})}}}}
@Composable private fun MultiChoiceField(label:String,options:List<String>,selected:Set<String>,maximum:Int,toggle:(String)->Unit){Column(Modifier.fillMaxWidth()){Text("$label · ${selected.size}/$maximum",color=Lavender,fontWeight=FontWeight.SemiBold);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){options.forEach{option->FilterChip(option in selected,{toggle(option)},label={Text(option)},enabled=option in selected||selected.size<maximum)}}}}
@Composable private fun EditorSection(title:String,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().glassCard(),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,color=Lavender,fontWeight=FontWeight.Bold);content()}}
@Composable private fun ProfileEditor(vm:AppViewModel,dismiss:()->Unit){
    val user=vm.user.value;val profile=user?.objectOrNull("profile");var photos by remember{mutableStateOf(user?.array("photos")?:JsonArray(emptyList()))}
    var name by remember{mutableStateOf(profile?.str("displayName").orEmpty())};var bio by remember{mutableStateOf(profile?.str("bio").orEmpty())};var gender by remember{mutableStateOf(profile?.strOrNull("gender")?:"Not specified")};var identity by remember{mutableStateOf(profile?.strOrNull("identity").orEmpty())};var pronouns by remember{mutableStateOf(profile?.strOrNull("pronouns")?:"Prefer not to say")};var position by remember{mutableStateOf(profile?.strOrNull("position")?:"Prefer not to say")};var cut by remember{mutableStateOf(profile?.strOrNull("circumcisionStatus")?:"Do not show")};var bodyType by remember{mutableStateOf(profile?.strOrNull("bodyType")?:"Do not show")};var relationship by remember{mutableStateOf(profile?.strOrNull("relationshipStatus")?:"Do not show")};var catchMeAt by remember{mutableStateOf(profile?.strOrNull("catchMeAt").orEmpty())};var explicitPics by remember{mutableStateOf(profile?.strOrNull("acceptExplicitPics")?:"Never")}
    var race by remember{mutableStateOf(profile?.strOrNull("race").orEmpty())};var bodyHair by remember{mutableStateOf(profile?.strOrNull("bodyHair").orEmpty())};var religion by remember{mutableStateOf(profile?.strOrNull("religion").orEmpty())};var interests by remember{mutableStateOf(profile?.array("interests")?.joinToString(", "){it.jsonPrimitive.content}.orEmpty())};var tags by remember{mutableStateOf(profile?.array("tags")?.joinToString(", "){it.jsonPrimitive.content}.orEmpty())}
    var intentions by remember{mutableStateOf(profile?.array("intentions")?.mapNotNull{it.jsonPrimitive.contentOrNull}?.toSet()?:emptySet())};var tribes by remember{mutableStateOf(profile?.strOrNull("clanTribe")?.split(',')?.map{it.trim()}?.filter{it.isNotBlank()}?.toSet()?:emptySet())};var kinks by remember{mutableStateOf(profile?.array("kinks")?.mapNotNull{it.jsonPrimitive.contentOrNull}?.toSet()?:emptySet())}
    var height by remember{mutableStateOf(profile?.get("heightCm")?.jsonPrimitive?.intOrNull?.toString().orEmpty())};var weight by remember{mutableStateOf(profile?.get("weightKg")?.jsonPrimitive?.intOrNull?.toString().orEmpty())};var hivStatus by remember{mutableStateOf(profile?.strOrNull("hivStatus")?:"Do not show")};var hivDate by remember{mutableStateOf(profile?.strOrNull("hivLastTestDate")?.take(10).orEmpty())};var prep by remember{mutableStateOf(profile?.strOrNull("prepStatus").orEmpty())};var visible by remember{mutableStateOf(profile?.get("visible")?.jsonPrimitive?.booleanOrNull?:true)}
    val social=profile?.objectOrNull("socialLinks");var instagram by remember{mutableStateOf(social?.strOrNull("instagram").orEmpty())};var facebook by remember{mutableStateOf(social?.strOrNull("facebook").orEmpty())};var tiktok by remember{mutableStateOf(social?.strOrNull("tiktok").orEmpty())};var xLink by remember{mutableStateOf(social?.strOrNull("x").orEmpty())};var snapchat by remember{mutableStateOf(social?.strOrNull("snapchat").orEmpty())};var website by remember{mutableStateOf(social?.strOrNull("website").orEmpty())};var saving by remember{mutableStateOf(false)}
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{vm.runTask{uploadFileFromUri(context,it,"profiles/photos","photo");vm.refresh();photos=vm.user.value?.array("photos")?:JsonArray(emptyList())}}}
    val genderOptions=listOf("Male","Cis Male","Trans Male","Non-Binary","Man","Woman","Non-binary","Not specified");val intentionOptions=listOf("Friends","Networking","Fun","Relationship","The Love of My Life","DL");val pronounOptions=listOf("He/Him/His","She/Her/Hers","They/Them/Theirs","Ze/Hir/Hirs","Ze/Zir/Zirs","Use my name only","Ask me","Prefer not to say");val tribeOptions=listOf("Bear","Clean Cut","Daddy","Discreet","Geek","Interracial","Jock","Leather","Otter","Poz","Rugged","Trans","Twink","Sober");val kinkOptions=listOf("Group","Couples","One on One","Oral Only","Threesomes","Public","BDSM","Slave/Master","Toy Fun")
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=Panel){Column(Modifier.fillMaxWidth().heightIn(max=790.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("Edit profile",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White)
        EditorSection("Profile photos"){if(photos.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){photos.forEach{item->val photo=item.jsonObject;Box{AsyncImage(authenticatedImageModel(photo.strOrNull("url")),null,Modifier.size(86.dp,104.dp).clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Crop);IconButton({vm.runTask{ApiClient.request("profiles/photos/${photo.str("id")}",HttpMethod.Delete);vm.refresh();photos=vm.user.value?.array("photos")?:JsonArray(emptyList())}},Modifier.align(Alignment.TopEnd).size(30.dp).background(Color.Black.copy(alpha=.65f),CircleShape)){Icon(Icons.Default.Close,"Remove photo",tint=Color.White,modifier=Modifier.size(17.dp))}}}};OutlinedButton({picker.launch("image/*")},Modifier.fillMaxWidth()){Icon(Icons.Default.AddPhotoAlternate,null);Text(" Add profile photo")}}
        EditorSection("Your profile"){OutlinedTextField(name,{name=it.take(40)},label={Text("Display name")},modifier=Modifier.fillMaxWidth());SelectField("Gender",gender,genderOptions,select={gender=it});MultiChoiceField("Looking for",intentionOptions,intentions,3){intentions=if(it in intentions)intentions-it else intentions+it};OutlinedTextField(bio,{bio=it.take(500)},label={Text("Bio")},minLines=4,modifier=Modifier.fillMaxWidth());PrivacyToggle("Visible in discovery",visible){visible=it}}
        EditorSection("Identity"){SelectField("Identity",identity,listOf("","Gay","Bisexual","Straight"),select={identity=it});SelectField("Pronouns",pronouns,pronounOptions,select={pronouns=it});SelectField("Position",position,listOf("Prefer not to say","Top","Top / Vers","Vers","Bottom / Vers","Bottom","Side"),select={position=it});SelectField("Cut / Uncut",cut,listOf("Do not show","Cut","Uncut"),select={cut=it});SelectField("Body type",bodyType,listOf("Do not show","Toned","Average","Large","Muscular","Slim","Stocky"),select={bodyType=it});SelectField("Relationship",relationship,listOf("Do not show","Committed","Dating","Engaged","Exclusive","Married","Open Relationship","Partnered","Single"),select={relationship=it});SelectField("Catch me at",catchMeAt,listOf("","My place","Your place","Coffee shop","Public area","Other"),select={catchMeAt=it});SelectField("Explicit pictures",explicitPics,listOf("Never","Not at first","Yes Please"),select={explicitPics=it})}
        EditorSection("About you"){OutlinedTextField(race,{race=it.take(60)},label={Text("Race")},modifier=Modifier.fillMaxWidth());OutlinedTextField(bodyHair,{bodyHair=it.take(60)},label={Text("Body hair")},modifier=Modifier.fillMaxWidth());OutlinedTextField(religion,{religion=it.take(80)},label={Text("Religion")},modifier=Modifier.fillMaxWidth());MultiChoiceField("Tribes",tribeOptions,tribes,3){tribes=if(it in tribes)tribes-it else tribes+it};OutlinedTextField(interests,{interests=it},label={Text("Interests, comma separated · max 12")},modifier=Modifier.fillMaxWidth());OutlinedTextField(tags,{tags=it},label={Text("Tags, comma separated · max 10")},modifier=Modifier.fillMaxWidth());MultiChoiceField("Kinks",kinkOptions,kinks,3){kinks=if(it in kinks)kinks-it else kinks+it}}
        EditorSection("Physical & sexual health"){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(height,{height=it.filter(Char::isDigit).take(3)},label={Text("Height cm")},modifier=Modifier.weight(1f));OutlinedTextField(weight,{weight=it.filter(Char::isDigit).take(3)},label={Text("Weight kg")},modifier=Modifier.weight(1f))};SelectField("HIV status",hivStatus,listOf("Do not show","Negative","Positive","Positive, Undetectable","Lets chat about it"),select={hivStatus=it});OutlinedTextField(hivDate,{hivDate=it.take(10)},label={Text("Last test date · YYYY-MM-DD")},modifier=Modifier.fillMaxWidth());SelectField("PrEP status",prep,listOf("","On PrEP","Not on PrEP"),select={prep=it})}
        EditorSection("Social links"){listOf("Instagram" to instagram,"Facebook" to facebook,"TikTok" to tiktok,"X" to xLink,"Snapchat" to snapchat,"Website" to website).forEach{(label,value)->OutlinedTextField(value,{next->when(label){"Instagram"->instagram=next;"Facebook"->facebook=next;"TikTok"->tiktok=next;"X"->xLink=next;"Snapchat"->snapchat=next;else->website=next}},label={Text("$label handle or URL")},modifier=Modifier.fillMaxWidth())}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(dismiss,Modifier.weight(1f)){Text("Cancel")};ElectricButton({val parsedInterests=interests.split(',').map{it.trim()}.filter{it.isNotBlank()}.distinct().take(12);val parsedTags=tags.split(',').map{it.trim().removePrefix("#")}.filter{it.isNotBlank()}.distinctBy{it.lowercase()}.take(10);saving=true;vm.runTask{try{ApiClient.request("profiles/me",HttpMethod.Patch,buildJsonObject{put("displayName",name.trim());put("bio",bio.trim());put("gender",gender);put("identity",identity);put("pronouns",pronouns);put("position",position);put("circumcisionStatus",cut);put("bodyType",bodyType);put("relationshipStatus",relationship);put("catchMeAt",catchMeAt);put("acceptExplicitPics",explicitPics);put("race",race.trim());put("bodyHair",bodyHair.trim());put("religion",religion.trim());put("clanTribe",tribes.sorted().joinToString(","));put("interests",buildJsonArray{parsedInterests.forEach{add(it)}});put("tags",buildJsonArray{parsedTags.forEach{add(it)}});put("intentions",buildJsonArray{intentions.forEach{add(it)}});put("kinks",buildJsonArray{kinks.forEach{add(it)}});put("heightCm",height.toIntOrNull()?.let{JsonPrimitive(it)}?:JsonNull);put("weightKg",weight.toIntOrNull()?.let{JsonPrimitive(it)}?:JsonNull);put("hivStatus",hivStatus);put("hivLastTestDate",hivDate.takeIf{it.isNotBlank()}?.let{JsonPrimitive(it)}?:JsonNull);put("prepStatus",prep.takeIf{it.isNotBlank()}?.let{JsonPrimitive(it)}?:JsonNull);put("visible",visible);put("socialLinks",buildJsonObject{put("instagram",instagram.trim());put("facebook",facebook.trim());put("tiktok",tiktok.trim());put("x",xLink.trim());put("snapchat",snapchat.trim());put("website",website.trim())})});vm.refresh();dismiss()}finally{saving=false}}},Modifier.weight(1f),enabled=!saving&&name.trim().length>=2){if(saving)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else Text("Save")}}
        Spacer(Modifier.height(28.dp))
    }}
}

@Composable private fun SharedGalleries(vm:AppViewModel,dismiss:()->Unit){var data by remember{mutableStateOf<JsonObject?>(null)};LaunchedEffect(Unit){vm.runTask{data=ApiClient.request("private-galleries/sharing").jsonObject}};AlertDialog(dismiss,title={Text("Shared galleries")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())){val owned=data?.array("sharedWith")?:JsonArray(emptyList());val received=data?.array("sharedWithMe")?:JsonArray(emptyList());Text("Shared by you",fontWeight=FontWeight.Bold,color=Lavender);if(owned.isEmpty())Text("Nothing shared yet",color=Muted);owned.forEach{g->val o=g.jsonObject;ListItem(headlineContent={Text(o.str("title"))},supportingContent={Text("${o.array("photos").size} photos · ${o.array("recipients").size} recipients")})};Spacer(Modifier.height(12.dp));Text("Shared with you",fontWeight=FontWeight.Bold,color=Lavender);if(received.isEmpty())Text("Nothing shared with you yet",color=Muted);received.forEach{g->val o=g.jsonObject;ListItem(headlineContent={Text(o.str("title"))},supportingContent={Text("From ${o["owner"]?.jsonObject?.str("name").orEmpty()}")})}}},confirmButton={TextButton(dismiss){Text("Done")}})}
