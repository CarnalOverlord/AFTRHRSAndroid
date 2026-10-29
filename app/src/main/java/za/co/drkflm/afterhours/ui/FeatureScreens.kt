@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package za.co.drkflm.afterhours.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.app.Activity
import android.view.WindowManager
import android.view.WindowManager.SCREEN_RECORDING_STATE_VISIBLE
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.function.Consumer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.res.painterResource
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
import za.co.drkflm.afterhours.R
import za.co.drkflm.afterhours.data.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import android.text.format.DateUtils
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView

private fun String?.visibleProfileValue():String? = this?.trim()?.takeIf {
    it.isNotEmpty() && it.lowercase() !in setOf("unavailable","hidden","unknown","not set","n/a","null","off")
}
private val chatUrlPattern=Regex("(?:https?://|www\\.)[^\\s<>\\\"']+",RegexOption.IGNORE_CASE)
private fun chatLink(body:String):String?=chatUrlPattern.find(body)?.value?.trimEnd(*"),.!?;:".toCharArray())?.let { raw ->
    val link=if(raw.startsWith("www.",ignoreCase=true)) "https://$raw" else raw
    runCatching { Uri.parse(link) }.getOrNull()?.takeIf { it.scheme?.lowercase() in setOf("http","https") && !it.host.isNullOrBlank() }?.toString()
}

@Composable private fun Header(title:String,subtitle:String?=null,action:(@Composable () -> Unit)?=null){Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White);subtitle?.let{Text(it,color=Muted)}};action?.invoke()}}
@Composable private fun authenticatedImageModel(url:String?):ImageRequest { val context=LocalContext.current; return ImageRequest.Builder(context).data(ApiClient.mediaUrl(url)).httpHeaders(ApiClient.imageHeaders()).build() }
@Composable private fun Avatar(url:String?,modifier:Modifier=Modifier.size(62.dp)){AsyncImage(authenticatedImageModel(url),null,modifier.clip(CircleShape).background(Raised),contentScale=ContentScale.Crop)}

private data class DiscoveryFilters(val online:Boolean=false,val minAge:String="",val maxAge:String="",val bodyType:String="",val minHeight:String="",val maxHeight:String="",val minWeight:String="",val maxWeight:String="",val relationshipStatus:String="",val race:String="",val bodyHair:String="",val religion:String="",val tribes:Set<String> = emptySet(),val circumcisionStatus:String="",val position:String="",val kinks:Set<String> = emptySet(),val lookingFor:String="",val interests:String="",val photo:String="all")
private fun discoveryPath(radius:Int,filters:DiscoveryFilters,advanced:Boolean):String{val values=mutableListOf("radius" to radius.toString());if(advanced){if(filters.online)values+="online" to "true";listOf("minAge" to filters.minAge,"maxAge" to filters.maxAge,"bodyType" to filters.bodyType,"minHeight" to filters.minHeight,"maxHeight" to filters.maxHeight,"minWeight" to filters.minWeight,"maxWeight" to filters.maxWeight,"relationshipStatus" to filters.relationshipStatus,"race" to filters.race,"bodyHair" to filters.bodyHair,"religion" to filters.religion,"circumcisionStatus" to filters.circumcisionStatus,"position" to filters.position,"lookingFor" to filters.lookingFor,"interests" to filters.interests).filter{it.second.isNotBlank()}.forEach{values+=it};if(filters.tribes.isNotEmpty())values+="clanTribe" to filters.tribes.joinToString(",");if(filters.kinks.isNotEmpty())values+="kink" to filters.kinks.joinToString(",");if(filters.photo!="all")values+="photo" to filters.photo};return "discovery?"+values.joinToString("&"){(key,value)->"$key=${URLEncoder.encode(value,StandardCharsets.UTF_8.toString())}"}}

@Composable fun DiscoverScreen(vm:AppViewModel) {
    var items by remember { mutableStateOf<JsonArray>(JsonArray(emptyList())) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var filters by remember { mutableStateOf(false) }
    var radius by remember { mutableFloatStateOf(25f) }
    var radiusMax by remember { mutableIntStateOf(25) }
    var advanced by remember { mutableStateOf(false) }
    var criteria by remember { mutableStateOf(DiscoveryFilters()) }
    fun load(targetRadius:Float=radius,targetCriteria:DiscoveryFilters=criteria) { vm.runTask { loading=true;try{items=ApiClient.request(discoveryPath(targetRadius.toInt(),targetCriteria,advanced)).jsonObject.array("items")}finally{loading=false} } }
    LaunchedEffect(Unit) { vm.runTask { val config=ApiClient.request("discovery/filters").jsonObject;radiusMax=config.int("radiusMax").coerceAtLeast(25);advanced=config.bool("advanced");radius=(config.objectOrNull("preferences")?.get("radiusKm")?.jsonPrimitive?.floatOrNull?:25f).let { if (advanced && it == 0f) 0f else it.coerceIn(1f,radiusMax.toFloat()) };loading=true;try{items=ApiClient.request(discoveryPath(radius.toInt(),criteria,advanced)).jsonObject.array("items")}finally{loading=false} } }
    val gridRevision = Presence.revision.intValue
    LaunchedEffect(gridRevision) { if(gridRevision>0 && !loading) runCatching { items=ApiClient.request(discoveryPath(radius.toInt(),criteria,advanced)).jsonObject.array("items") } }
    LaunchedEffect(Unit) { while(true) { delay(30_000); if(!loading) runCatching { items=ApiClient.request(discoveryPath(radius.toInt(),criteria,advanced)).jsonObject.array("items") } } }
    selected?.let { ProfileScreen(vm,it.str("id")){selected=null}; return }
    Column(Modifier.fillMaxSize()) {
        Header("The Grid","Nearby members") { IconButton({filters=true}){Icon(Icons.Default.Tune,"Filters")} }
        PullToRefreshBox(isRefreshing=loading,onRefresh={load()},modifier=Modifier.weight(1f)) {
            LazyVerticalGridCompat(items) { profile -> ProfileTile(profile){selected=profile} }
        }
    }
    if(filters) DiscoveryFilterSheet(radius,criteria,radiusMax,advanced,{filters=false}){newRadius,newCriteria->radius=newRadius;criteria=newCriteria;filters=false;load(newRadius,newCriteria)}
}
@Composable private fun DiscoveryNumberField(label:String,value:String,range:IntRange,change:(String)->Unit){OutlinedTextField(value,{raw->val digits=raw.filter(Char::isDigit);change(if(digits.isBlank())""else digits.toIntOrNull()?.coerceIn(range.first,range.last)?.toString().orEmpty())},label={Text(label)},placeholder={Text("Any")},modifier=Modifier.fillMaxWidth())}
@Composable private fun DiscoveryFilterSheet(initialRadius:Float,initial:DiscoveryFilters,radiusMax:Int,advanced:Boolean,dismiss:()->Unit,apply:(Float,DiscoveryFilters)->Unit){var radius by remember{mutableFloatStateOf(initialRadius)};var f by remember{mutableStateOf(initial)};val races=listOf("Black","Coloured","Indian / South Asian","White","Asian","Mixed","Other");val bodies=listOf("Toned","Average","Large","Muscular","Slim","Stocky");val relationships=listOf("Committed","Dating","Engaged","Exclusive","Married","Open Relationship","Partnered","Single");val tribes=listOf("Bear","Clean Cut","Daddy","Discreet","Geek","Interracial","Jock","Leather","Otter","Poz","Rugged","Trans","Twink","Sober");val hair=listOf("Smooth","Some","Hairy","Very hairy");val religions=listOf("African traditional spirituality","Christianity","Islam","Hinduism","Judaism","Buddhism","Agnostic","Atheist","Spiritual","Other");val positions=listOf("Top","Top / Vers","Vers","Bottom / Vers","Bottom","Side");val kinks=listOf("Group","Couples","One on One","Oral Only","Threesomes","Public","BDSM","Slave/Master","Toy Fun");ModalBottomSheet(onDismissRequest=dismiss,containerColor=Panel){Column(Modifier.fillMaxWidth().heightIn(max=790.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("Find your vibe",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White);EditorSection("Distance"){if(advanced)PrivacyToggle("Unlimited distance",radius==0f){radius=if(it)0f else radiusMax.toFloat()};Text(if(advanced&&radius==0f)"Show members at any distance"else"Within ${radius.toInt()} km",color=Lavender);if(radius!=0f)Slider(radius,{radius=it},valueRange=1f..radiusMax.toFloat(),steps=(radiusMax-2).coerceAtLeast(0));if(!advanced)Text("Free members are limited to 25 km.",color=Muted,style=MaterialTheme.typography.bodySmall)};EditorSection(if(advanced)"Profile filters"else"Profile filters · Premium"){Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(9.dp)){PrivacyToggle("Online members only",f.online,advanced){f=f.copy(online=it)};DiscoveryNumberField("Minimum age",f.minAge,18..99){f=f.copy(minAge=it)};DiscoveryNumberField("Maximum age",f.maxAge,18..99){f=f.copy(maxAge=it)};SelectField("Body type",f.bodyType,listOf("")+bodies,{if(it.isBlank())"Any body type"else it}){f=f.copy(bodyType=it)};DiscoveryNumberField("Minimum height (cm)",f.minHeight,100..250){f=f.copy(minHeight=it)};DiscoveryNumberField("Maximum height (cm)",f.maxHeight,100..250){f=f.copy(maxHeight=it)};DiscoveryNumberField("Minimum weight (kg)",f.minWeight,30..300){f=f.copy(minWeight=it)};DiscoveryNumberField("Maximum weight (kg)",f.maxWeight,30..300){f=f.copy(maxWeight=it)};SelectField("Relationship",f.relationshipStatus,listOf("")+relationships,{if(it.isBlank())"Any relationship"else it}){f=f.copy(relationshipStatus=it)};SelectField("Race",f.race,listOf("")+races,{if(it.isBlank())"Any race"else it}){f=f.copy(race=it)};SelectField("Body hair",f.bodyHair,listOf("")+hair,{if(it.isBlank())"Any body hair"else it}){f=f.copy(bodyHair=it)};SelectField("Religion",f.religion,listOf("")+religions,{if(it.isBlank())"Any religion"else it}){f=f.copy(religion=it)};MultiChoiceField("Tribes · any match",tribes,f.tribes,tribes.size){f=f.copy(tribes=if(it in f.tribes)f.tribes-it else f.tribes+it)};SelectField("Cut / Uncut",f.circumcisionStatus,listOf("","Cut","Uncut"),{if(it.isBlank())"Any"else it}){f=f.copy(circumcisionStatus=it)};SelectField("Position",f.position,listOf("")+positions,{if(it.isBlank())"Any position"else it}){f=f.copy(position=it)};MultiChoiceField("Kinks · any match",kinks,f.kinks,3){f=f.copy(kinks=if(it in f.kinks)f.kinks-it else f.kinks+it)};OutlinedTextField(f.lookingFor,{f=f.copy(lookingFor=it)},label={Text("Looking for · comma separated")},modifier=Modifier.fillMaxWidth());OutlinedTextField(f.interests,{f=f.copy(interests=it)},label={Text("Interests · comma separated")},modifier=Modifier.fillMaxWidth());SelectField("Profile picture",f.photo,listOf("all","with","without"),{when(it){"with"->"With a picture";"without"->"Without a picture";else->"All profiles"}}){f=f.copy(photo=it)}};if(!advanced)Text("Upgrade to Premium to use profile and online filters.",color=Muted,style=MaterialTheme.typography.bodySmall)};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton({f=DiscoveryFilters();radius=radiusMax.toFloat()},Modifier.weight(1f)){Text("Clear all")};ElectricButton({apply(radius,f)},Modifier.weight(1f)){Text("Apply")}};Spacer(Modifier.height(28.dp))}}}
@Composable private fun LazyVerticalGridCompat(items:JsonArray,item:@Composable (JsonObject) -> Unit){LazyColumn(contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp)){items(items.chunked(2)){row->Row(Modifier.fillMaxWidth()){row.forEach{Box(Modifier.weight(1f).padding(6.dp)){item(it.jsonObject)}};if(row.size==1)Spacer(Modifier.weight(1f))}}}}
private fun positionGlyph(position:String)=when(position){"Top"->"↑";"Top / Vers"->"↗";"Vers"->"↕";"Bottom / Vers"->"↘";"Bottom"->"↓";"Side"->"↔";else->""}
@Composable private fun GridChip(text:String,symbol:String?=null){Row(Modifier.border(1.dp,Electric.copy(alpha=.8f),RoundedCornerShape(50)).background(Electric.copy(alpha=.13f),RoundedCornerShape(50)).padding(horizontal=7.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){symbol?.takeIf{it.isNotBlank()}?.let{Text(it,color=Lavender,style=MaterialTheme.typography.labelSmall)};Text(text,color=Lavender,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold,maxLines=1)}}
@Composable private fun ProfileTile(p:JsonObject,open:()->Unit){
    var liked by remember(p.str("id"),p.bool("liked")){mutableStateOf(p.bool("liked"))}
    var liking by remember(p.str("id")){mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val shape=RoundedCornerShape(20.dp)
    val position=p.strOrNull("position").visibleProfileValue()?.takeUnless{it=="Prefer not to say"}
    val status=p.strOrNull("circumcisionStatus")?.takeIf{it=="Cut"||it=="Uncut"}
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Raised).border(if(p.bool("chatted"))2.5.dp else 1.dp,if(p.bool("chatted"))Electric else Color.White.copy(alpha=.12f),shape).clickable(onClick=open)){
        AsyncImage(authenticatedImageModel(p.strOrNull("photo")),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent,.46f to Color.Black.copy(alpha=.12f),1f to Color.Black.copy(alpha=.96f))))
        Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(43.dp).clip(CircleShape).background(Color(0xBB333039)).clickable(enabled=!liking){
            liking=true;scope.launch {try {ApiClient.request("likes/${p.str("id")}",if(liked)HttpMethod.Delete else HttpMethod.Post);liked=!liked}catch(_:Exception){}finally{liking=false}}
        },contentAlignment=Alignment.Center){Icon(if(liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,"Like ${p.str("displayName")}",tint=if(liked) Color(0xFFFF29B7) else Color.White,modifier=Modifier.size(26.dp))}
        val presenceState=p.strOrNull("presenceState") ?: if(p.bool("online")) "ONLINE" else "OFFLINE"
        if(presenceState=="ONLINE" || presenceState=="RECENT") Row(Modifier.align(Alignment.TopStart).padding(9.dp).background(Color(0xCC14221B),RoundedCornerShape(12.dp)).padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Box(Modifier.size(8.dp).background(if(presenceState=="ONLINE")Color(0xFF43D17A)else Color(0xFFFFC66D),CircleShape));Text(if(presenceState=="ONLINE")"Online" else "Recently active",color=Color.White,style=MaterialTheme.typography.labelSmall)}
        Column(Modifier.matchParentSize().padding(9.dp)){
            Spacer(Modifier.weight(1f))
            Text("${p.str("displayName")}${p["age"]?.jsonPrimitive?.contentOrNull?.let{", $it"}.orEmpty()}",fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color.White,style=MaterialTheme.typography.titleMedium)
            if(position!=null||status!=null)Row(Modifier.padding(top=5.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){position?.let{GridChip(it,positionGlyph(it))};status?.let{GridChip(it,if(it=="Cut")"✂" else "○")}}
        }
    }
}

private fun publicProfileText(value:String?):String? = value.visibleProfileValue()?.takeUnless { it.equals("Prefer not to say",true) || it.equals("Do not show",true) }
@Composable private fun ProfileDetailsSection(title:String, entries:List<Pair<String,String?>>) {
    val visible=entries.mapNotNull { (label,value) -> publicProfileText(value)?.let { label to it } }
    if(visible.isNotEmpty()) Column(Modifier.fillMaxWidth().glassCard(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=Color.White)
        visible.forEach { (label,value) -> Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,color=Muted,style=MaterialTheme.typography.labelMedium)
            Text(value,color=Color.White)
        } }
    }
}
private fun JsonObject.publicList(key:String):String? = array(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::publicProfileText) }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
private fun JsonObject.publicString(key:String):String? = publicProfileText(strOrNull(key))

@Composable fun ProfileScreen(vm:AppViewModel,id:String,back:()->Unit){
    BackHandler(onBack=back)
    var p by remember{mutableStateOf<JsonObject?>(null)}
    var safety by remember{mutableStateOf(false)}
    var liked by remember(id){mutableStateOf(false)}
    var liking by remember(id){mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(id){vm.runTask{val data=ApiClient.request("profiles/$id").jsonObject;p=data;liked=data.bool("liked")}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
        Header(p?.objectOrNull("profile")?.str("displayName")?:"Profile"){IconButton(back){Icon(Icons.Default.ArrowBack,"Back",tint=Color.White)}}
        p?.let{data->
            val profile=data.objectOrNull("profile")?:return@let
            val photos=data.array("photos")
            if(photos.isNotEmpty()){
                val pagerState=androidx.compose.foundation.pager.rememberPagerState(pageCount={photos.size})
                Box(Modifier.fillMaxWidth().height(430.dp)){
                    androidx.compose.foundation.pager.HorizontalPager(state=pagerState,modifier=Modifier.fillMaxSize()) { page ->
                        AsyncImage(authenticatedImageModel(photos[page].jsonObject.str("url")),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                    }
                    if(photos.size>1){
                        Row(Modifier.align(Alignment.BottomCenter).padding(bottom=12.dp).background(Color.Black.copy(alpha=.6f),RoundedCornerShape(20.dp)).padding(horizontal=12.dp,vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
                            photos.indices.forEach { index ->
                                Box(Modifier.size(if(index==pagerState.currentPage)8.dp else 6.dp).background(if(index==pagerState.currentPage)Color.White else Color.White.copy(alpha=.4f),CircleShape))
                            }
                            Text("${pagerState.currentPage+1}/${photos.size}",color=Color.White,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                Text("${profile.str("displayName")}${data["age"]?.jsonPrimitive?.contentOrNull?.let{", $it"}.orEmpty()}",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White)
                val location=data.objectOrNull("location")
                val metadata=listOf(location?.strOrNull("distance").visibleProfileValue(),profile.strOrNull("presenceLabel").visibleProfileValue()).filterNotNull()
                if(metadata.isNotEmpty())Text(metadata.joinToString(" · "),color=Lavender)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    ElectricButton({if(!liking){liking=true;scope.launch{try{val wasLiked=liked;ApiClient.request("likes/$id",if(wasLiked)HttpMethod.Delete else HttpMethod.Post);liked=!wasLiked}catch(e:Exception){vm.error.value=e.message?:"Could not update this like"}finally{liking=false}}}},Modifier.weight(1f)){Icon(if(liked)Icons.Default.Favorite else Icons.Default.FavoriteBorder,null);Text(if(liking)" Updating..." else if(liked)" Unlike" else " Like")}
                    ElectricButton({scope.launch{try{val conversation=ApiClient.request("conversations",HttpMethod.Post,obj("userId" to id)).jsonObject;val conversationId=conversation.str("id");if(conversationId.isBlank())throw IllegalStateException("Could not open conversation");vm.pendingConversation.value=conversationId}catch(e:Exception){vm.error.value=e.message?:"Could not open chat"}}},Modifier.weight(1f)){Icon(Icons.Default.Chat,null);Text(" Message")}
                }
                profile.publicString("bio")?.let { Text(it,Modifier.glassCard(),color=Color.White) }
                ProfileDetailsSection("About",listOf(
                    "Gender" to profile.publicString("gender"),
                    "Identity" to profile.publicString("identity"),
                    "Pronouns" to profile.publicString("pronouns"),
                    "Position" to profile.publicString("position"),
                    "Cut / Uncut" to profile.publicString("circumcisionStatus"),
                    "Body type" to profile.publicString("bodyType"),
                    "Relationship" to profile.publicString("relationshipStatus"),
                    "Catch me at" to profile.publicString("catchMeAt"),
                    "Explicit pictures" to profile.publicString("acceptExplicitPics"),
                    "Race" to profile.publicString("race"),
                    "Body hair" to profile.publicString("bodyHair"),
                    "Religion" to profile.publicString("religion"),
                    "Tribes" to profile.publicString("clanTribe"),
                    "Height" to profile["heightCm"]?.jsonPrimitive?.intOrNull?.let { "$it cm" },
                    "Weight" to profile["weightKg"]?.jsonPrimitive?.intOrNull?.let { "$it kg" }
                ))
                ProfileDetailsSection("Looking for",listOf(
                    "Intentions" to profile.publicList("intentions"),
                    "Interests" to profile.publicList("interests"),
                    "Kinks" to profile.publicList("kinks"),
                    "Tags" to profile.publicList("tags")
                ))
                ProfileDetailsSection("Health",listOf(
                    "HIV status" to profile.publicString("hivStatus"),
                    "Last HIV test" to (if(profile.publicString("hivStatus")!=null) profile.publicString("hivLastTestDate")?.take(10) else null),
                    "PrEP" to profile.publicString("prepStatus")
                ))
                profile.objectOrNull("socialLinks")?.let { links ->
                    ProfileDetailsSection("Social links",listOf(
                        "Instagram" to links.publicString("instagram"),
                        "Facebook" to links.publicString("facebook"),
                        "TikTok" to links.publicString("tiktok"),
                        "X" to links.publicString("x"),
                        "Snapchat" to links.publicString("snapchat"),
                        "Website" to links.publicString("website")
                    ))
                }
                TextButton({safety=true},Modifier.align(Alignment.CenterHorizontally)){Text("Block or report",color=Color(0xFFFFB4AB))}
            }
        }
    }
    if(safety)AlertDialog({safety=false},title={Text("Safety actions")},text={Text("You can report this profile for review, block it so you no longer see each other, or do both.")},confirmButton={TextButton({vm.runTask{ApiClient.request("reports",HttpMethod.Post,obj("userId" to id,"reason" to "OTHER","details" to "Reported from Android app"));ApiClient.request("blocks",HttpMethod.Post,obj("userId" to id))};safety=false}){Text("Report & block")}},dismissButton={TextButton({safety=false}){Text("Cancel")}})
}

@Composable fun LikesScreen(vm:AppViewModel){var tab by remember{mutableIntStateOf(0)};var items by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))};var selected by remember{mutableStateOf<String?>(null)};suspend fun load(){val data=ApiClient.request(when(tab){0->"likes/received";1->"likes";else->"likes/matches"});items=if(data is JsonArray)data else data.jsonObject.array("items")};LaunchedEffect(tab){vm.runTask{load()}};selected?.let{ProfileScreen(vm,it){selected=null};return};Column{Header("Likes","Connections waiting in the wings");TabRow(tab){Tab(tab==0,{tab=0},text={Text("Liked you")});Tab(tab==1,{tab=1},text={Text("You liked")});Tab(tab==2,{tab=2},text={Text("Matches")})};LazyColumn{if(items.isEmpty())item{Text("No likes here yet.",color=Muted,modifier=Modifier.padding(24.dp))};items(items.size){i->val p=items[i].jsonObject;val detail=p.strOrNull("bio").visibleProfileValue()?:p.strOrNull("city").visibleProfileValue();ListItem(headlineContent={Text(p.str("displayName"),fontWeight=FontWeight.Bold)},supportingContent=detail?.let{{Text(it,maxLines=2)}},leadingContent={Avatar(p.strOrNull("photo"))},modifier=Modifier.clickable{selected=p.str("id")})}}}
}

private fun conversationPreview(c:JsonObject):String { if(c.str("status")=="REQUESTED")return "Message request";val m=c["lastMessage"]?.jsonObject?:return "Start chatting";val body=m.str("body");if(body.startsWith("GALLERY_SHARE:"))return "Private gallery shared";if(body.startsWith("LOCATION:"))return "📍 Shared location";return when(m.str("kind").uppercase()){ "IMAGE","GIF"->"Image received";"VIDEO"->"Video received";"GALLERY"->"Private gallery shared";else->body } }
@Composable fun InboxScreen(vm:AppViewModel, chatsReselect:Int=0){
    var tab by remember{mutableIntStateOf(0)}
    var items by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))}
    var chat by remember{mutableStateOf<JsonObject?>(null)}
    LaunchedEffect(chatsReselect){if(chatsReselect>0)chat=null}
    var newGroup by remember{mutableStateOf(false)}
    var deleteTarget by remember{mutableStateOf<JsonObject?>(null)}
    var deleting by remember{mutableStateOf(false)}
    var deleteScope by remember{mutableStateOf("me")}
    val isPremium=vm.user.value?.str("subscription")=="PREMIUM" || vm.user.value?.str("role")=="ADMIN"
    suspend fun load(){items=ApiClient.request(if(tab==0)"conversations" else "group-chats").jsonArray;vm.refreshUnread()}
    LaunchedEffect(tab){vm.runTask{load()}}
    val inboxRevision = Presence.revision.intValue
    LaunchedEffect(tab,inboxRevision) { if(inboxRevision>0) runCatching { load() } }
    LaunchedEffect(tab) { while(true) { delay(30_000); runCatching { load() } } }
    LaunchedEffect(vm.pendingConversation.value){vm.pendingConversation.value?.let{id->tab=0;try{val all=ApiClient.request("conversations").jsonArray;chat=all.firstOrNull{it.jsonObject.str("id")==id}?.jsonObject;if(chat==null)vm.error.value="Conversation could not be found. Please try again."}catch(e:Exception){vm.error.value=e.message?:"Could not load conversation"}finally{vm.pendingConversation.value=null}}}
    LaunchedEffect(vm.pendingGroup.value){vm.pendingGroup.value?.let{id->tab=1;vm.runTask{items=ApiClient.request("group-chats").jsonArray;chat=items.firstOrNull{it.jsonObject.str("id")==id}?.jsonObject;vm.pendingGroup.value=null}}}
    chat?.let{if(tab==0)DirectChat(vm,it){chat=null;vm.runTask{load()}}else GroupChatScreen(vm,it){chat=null;vm.runTask{load()}};return}
    Column{
        Header("Chats","Messages, requests and groups"){
            if(tab==1)IconButton({newGroup=true},Modifier.background(Electric.copy(alpha=.22f),CircleShape)){Icon(Icons.Default.GroupAdd,"Create group",tint=Electric)}
        }
        TabRow(tab){Tab(tab==0,{tab=0},text={Text("Direct")});Tab(tab==1,{tab=1},text={Text("Groups")})}
        LazyColumn(Modifier.fillMaxSize()){
            if(items.isEmpty())item{Box(Modifier.fillParentMaxSize().padding(36.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Icon(if(tab==0)Icons.Default.ChatBubbleOutline else Icons.Default.Groups,null,tint=Muted,modifier=Modifier.size(72.dp));Text(if(tab==0)"No messages yet"else"No group chats",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color.White);Text(if(tab==0)"Start a conversation from a member profile."else"Create a group with two or more mutual matches.",color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center);if(tab==1)ElectricButton({newGroup=true}){Icon(Icons.Default.GroupAdd,null);Text(" Create group")}}}}
            items(items.size){i->val c=items[i].jsonObject;val other=c["other"]?.jsonObject;ListItem(headlineContent={Text(if(tab==0)other?.str("displayName").orEmpty()else c.str("title"),fontWeight=FontWeight.Bold,color=Color.White)},supportingContent={if(tab==0)Column{Text(conversationPreview(c),maxLines=1,color=Muted);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Box(Modifier.size(7.dp).background(if(other?.bool("online")==true)Color(0xFF43D17A)else Color.Gray,CircleShape));Text(if(other?.bool("online")==true)"Online"else"Offline",color=Muted,style=MaterialTheme.typography.labelSmall)}}else Text(c.array("members").joinToString(", "){it.jsonObject.str("name")}.ifBlank{"${c.int("pendingCount")} pending"},maxLines=1,color=Muted)},leadingContent={if(tab==0)Avatar(other?.strOrNull("photo"))else if(!c.strOrNull("photoUrl").isNullOrBlank())Avatar(c.strOrNull("photoUrl"),Modifier.size(52.dp))else Box(Modifier.size(52.dp).background(Electric.copy(alpha=.18f),CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Default.Groups,null,tint=Lavender)}},trailingContent={if(c.int("unreadCount")>0)Badge{Text(c.int("unreadCount").toString())}},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.combinedClickable(onClick={chat=c},onLongClick={deleteScope="me";deleteTarget=c}));if(i<items.lastIndex)HorizontalDivider(modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp),thickness=0.7.dp,color=Electric.copy(alpha=0.24f))}
        }
    }
    deleteTarget?.let{target->
        val isGroup=tab==1
        AlertDialog(
            onDismissRequest={if(!deleting)deleteTarget=null},
            title={Text(if(isGroup)"Leave group chat?" else "Delete conversation?")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(isGroup)Text("This will leave the group and remove it from your chats. Other participants can continue chatting.")
                else {
                    Text("Choose who this conversation is deleted for:")
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth().clickable{deleteScope="me"}){
                        RadioButton(selected=deleteScope=="me",onClick={deleteScope="me"})
                        Text("Delete for me")
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth().clickable{if(isPremium)deleteScope="everyone"}){
                        RadioButton(selected=deleteScope=="everyone",onClick={deleteScope="everyone"},enabled=isPremium)
                        Column{
                            Text("Delete for both parties")
                            if(!isPremium)Text("Premium only",color=Lavender,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(if(deleteScope=="everyone")"Permanently deletes the conversation for both people. This cannot be undone." else "Removes the conversation from your chat list. The other person keeps their copy.",color=Muted,style=MaterialTheme.typography.bodySmall)
                }
            }},
            confirmButton={TextButton(onClick={
                if(!deleting)vm.runTask{
                    deleting=true
                    try{
                        if(isGroup)ApiClient.request("group-chats/${target.str("id")}/leave",HttpMethod.Post)
                        else ApiClient.request("conversations/${target.str("id")}?scope=$deleteScope",HttpMethod.Delete)
                        deleteTarget=null
                        load()
                    }finally{deleting=false}
                }
            },enabled=!deleting){Text(if(isGroup)"Leave group" else if(deleteScope=="everyone")"Delete for both" else "Delete for me",color=Color(0xFFFF777E))}},
            dismissButton={TextButton({deleteTarget=null},enabled=!deleting){Text("Cancel")}}
        )
    }
    if(newGroup)NewGroupSheet(vm,{newGroup=false}){created->newGroup=false;vm.runTask{load();chat=items.firstOrNull{it.jsonObject.str("id")==created}?.jsonObject}}
}

@Composable private fun NewGroupSheet(vm:AppViewModel,dismiss:()->Unit,created:(String)->Unit){
    var title by remember{mutableStateOf("")}
    var eligible by remember{mutableStateOf<JsonArray?>(null)}
    var selected by remember{mutableStateOf(setOf<String>())}
    var busy by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){vm.runTask{eligible=ApiClient.request("group-chats/eligible").jsonArray}}
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=Panel){Column(Modifier.fillMaxWidth().heightIn(max=760.dp).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("New group",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White)
        OutlinedTextField(title,{title=it.take(60)},label={Text("Group name")},placeholder={Text("Friday night crew")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Text("Invite mutual matches · ${selected.size}/10",color=Lavender,fontWeight=FontWeight.Bold)
        val people=eligible
        when{people==null->Box(Modifier.fillMaxWidth().height(220.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()};people.isEmpty()->Box(Modifier.fillMaxWidth().height(220.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.PersonOff,null,tint=Muted,modifier=Modifier.size(52.dp));Text("No eligible members",fontWeight=FontWeight.Bold);Text("Participants must be verified mutual matches who allow requests.",color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}};else->LazyColumn(Modifier.heightIn(max=390.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){items(people.size){index->val person=people[index].jsonObject;val id=person.str("id");val checked=id in selected;ListItem(headlineContent={Text(person.str("name"),fontWeight=FontWeight.SemiBold,color=Color.White)},leadingContent={Avatar(person.strOrNull("photo"),Modifier.size(46.dp))},trailingContent={Icon(if(checked)Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,null,tint=Lavender)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.clickable{selected=if(checked)selected-id else if(selected.size<10)selected+id else selected})}}}
        ElectricButton(onClick={busy=true;vm.runTask{try{val result=ApiClient.request("group-chats",HttpMethod.Post,obj("title" to title.trim(),"participantIds" to selected.toList())).jsonObject;created(result.str("id"))}finally{busy=false}}},enabled=title.trim().length>=2&&selected.size>=2&&!busy){if(busy)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else{Icon(Icons.Default.Groups,null);Text(" Create group")}}
        OutlinedButton(dismiss,Modifier.fillMaxWidth()){Text("Cancel")};Spacer(Modifier.height(22.dp))
    }}
}

@Composable private fun DirectChat(vm:AppViewModel,c:JsonObject,back:()->Unit){
    BackHandler(onBack=back)
    val context=LocalContext.current
    val id=c.str("id")
    var other by remember(c.str("id")) { mutableStateOf(c.objectOrNull("other")) }
    var requestStatus by remember(id){mutableStateOf(c.str("status"))}
    var requesterId by remember(id){mutableStateOf(c.str("requestedById"))}
    var responding by remember(id){mutableStateOf(false)}
    val isIncomingRequest=requestStatus=="REQUESTED" && requesterId!=vm.user.value?.str("id")
    suspend fun refreshRequest(){
        val current=ApiClient.request("conversations").jsonArray.firstOrNull{it.jsonObject.str("id")==id}?.jsonObject
        if(current!=null){requestStatus=current.str("status");requesterId=current.str("requestedById")}
    }

    val chatRevision = Presence.revision.intValue
    LaunchedEffect(id,chatRevision) { if(chatRevision>0) runCatching { other=ApiClient.request("conversations").jsonArray.firstOrNull { it.jsonObject.str("id")==id }?.jsonObject?.objectOrNull("other") ?: other } }
    LaunchedEffect(id) { while(true) { delay(30_000); runCatching { other=ApiClient.request("conversations").jsonArray.firstOrNull { it.jsonObject.str("id")==id }?.jsonObject?.objectOrNull("other") ?: other } } }
    var messages by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))}
    var viewedProfile by remember{mutableStateOf<String?>(null)}
    var text by remember{mutableStateOf("")}
    var replyingTo by remember { mutableStateOf<JsonObject?>(null) }
    var attach by remember{mutableStateOf(false)}
    var cameraFile by remember{mutableStateOf<File?>(null)}
    var chooseGallery by remember{mutableStateOf(false)}
    var openedGallery by remember{mutableStateOf<JsonObject?>(null)}
    var quickiePicker by remember { mutableStateOf(false) }
    var openedQuickie by remember { mutableStateOf<Pair<ByteArray,String>?>(null) }
    val listState=rememberLazyListState()
    suspend fun load(){messages=ApiClient.request("conversations/$id/messages").jsonArray;val unread=messages.map{it.jsonObject}.filter{it.str("senderId")!=vm.user.value?.str("id")}.map{it.str("id")}.filter{it.isNotBlank()};if(unread.isNotEmpty())runCatching{ApiClient.request("conversations/$id/read",HttpMethod.Post,buildJsonObject{put("messageIds",buildJsonArray{unread.forEach{add(it)}})})}}
    fun upload(file:File){vm.runTask{ApiClient.upload("conversations/$id/images",file);load()}}
    val quickieVideoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{vm.runTask{uploadFileFromUri(context,it,"conversations/$id/view-once","media");load()}}}
    val quickiePhotoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{vm.runTask{uploadFileFromUri(context,it,"conversations/$id/view-once","media");load()}}}
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{vm.runTask{uploadFileFromUri(context,it,"conversations/$id/images");load()}}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){saved->if(saved)cameraFile?.let(::upload)}
    fun openCamera(){val dir=File(context.cacheDir,"camera").apply{mkdirs()};val file=File(dir,"chat-${System.currentTimeMillis()}.jpg");cameraFile=file;camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.files",file))}
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)openCamera()else vm.error.value="Camera permission is required to take a photo."}
    LaunchedEffect(id){
        runCatching{refreshRequest();load()}
        while(true){
            delay(4_000)
            runCatching{
                val latest=ApiClient.request("conversations/$id/messages").jsonArray
                if(latest!=messages){
                    val previousIds=messages.map{it.jsonObject.str("id")}.toSet()
                    val newIncoming=latest.map{it.jsonObject}.filter{
                        it.str("id") !in previousIds && it.str("senderId")!=vm.user.value?.str("id")
                    }.map{it.str("id")}.filter{it.isNotBlank()}
                    messages=latest
                    if(newIncoming.isNotEmpty()){
                        runCatching{ApiClient.request("conversations/$id/read",HttpMethod.Post,buildJsonObject{
                            put("messageIds",buildJsonArray{newIncoming.forEach{add(it)}})
                        })}
                    }
                }
            }
        }
    }
    LaunchedEffect(messages.size){if(messages.isNotEmpty())listState.scrollToItem(messages.lastIndex)}
    viewedProfile?.let { profileId -> ProfileScreen(vm,profileId){viewedProfile=null};return }
    Column(Modifier.fillMaxSize().imePadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(back){Icon(Icons.Default.ArrowBack,"Back",tint=Color.White)}
            Avatar(other?.strOrNull("photo"),Modifier.size(46.dp).clickable { viewedProfile=other?.strOrNull("userId")?:other?.strOrNull("id") })
            Spacer(Modifier.width(12.dp))
            Column{Text(other?.str("displayName")?:"Chat",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color.White);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Box(Modifier.size(8.dp).background(if(other?.bool("online")==true)Color(0xFF43D17A)else Color.Gray,CircleShape));Text(if(other?.bool("online")==true)"Online"else"Offline",color=Muted,style=MaterialTheme.typography.bodySmall)}}
        }
        if(isIncomingRequest){
            Column(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp).background(Raised,RoundedCornerShape(14.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("Message request",color=Lavender,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                Text("Read the message below before deciding whether to chat.",color=Color.White)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Button(onClick={if(!responding){responding=true;vm.runTask{try{
                        ApiClient.request("conversations/$id/accept",HttpMethod.Post)
                        requestStatus="ACTIVE";refreshRequest();load()
                    }finally{responding=false}}}},enabled=!responding,modifier=Modifier.weight(1f)){Text("Accept")}
                    OutlinedButton(onClick={if(!responding){responding=true;vm.runTask{try{
                        ApiClient.request("conversations/$id/decline",HttpMethod.Post)
                        requestStatus="DECLINED";back()
                    }finally{responding=false}}}},enabled=!responding,modifier=Modifier.weight(1f)){Text("Decline")}
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            reverseLayout = false
        ) {
            items(messages.size) { i ->
                MessageBubble(
                    messages[i].jsonObject,
                    vm.user.value?.str("id"),
                    openGallery = { galleryId ->
                        vm.runTask {
                            openedGallery = ApiClient.request("private-galleries/$galleryId/view").jsonObject
                        }
                    },
                    openOnce = { messageId ->
                        vm.runTask {
                            val media = ApiClient.openOnce(id, messageId)
                            openedQuickie = media
                            load()
                        }
                    },
                    reply = { message -> replyingTo = message }
                )
            }
        }
        replyingTo?.let { original ->
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).background(Raised,RoundedCornerShape(12.dp)).padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text("Replying to message",color=Lavender,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium)
                    Text(replySnippet(original),maxLines=2,overflow=TextOverflow.Ellipsis,color=Color.White)
                }
                IconButton({replyingTo=null}){Icon(Icons.Default.Close,"Cancel reply",tint=Muted)}
            }
        }
        if(!isIncomingRequest){
        Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton({attach=true}){Icon(Icons.Default.AddCircle,"Attachments",tint=Lavender)}
            OutlinedTextField(text,{text=it},Modifier.weight(1f),placeholder={Text("Message",color=Muted)})
            IconButton({
                val body=text.trim()
                if(body.isNotEmpty()){
                    val target=replyingTo?.strOrNull("id")
                    text="";replyingTo=null
                    vm.runTask{
                        ApiClient.request("conversations/$id/messages",HttpMethod.Post,buildJsonObject{
                            put("body",body);put("kind","TEXT");if(target!=null)put("replyToId",target)
                        })
                        load()
                    }
                }
            }){Icon(Icons.Default.Send,"Send",tint=HotPink)}
        }
        }
    }
    if(attach)AttachmentSheet(dismiss={attach=false},vm=vm,send={body->vm.runTask{ApiClient.request("conversations/$id/messages",HttpMethod.Post,obj("body" to body,"kind" to "TEXT"));load()}},camera={attach=false;if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)openCamera()else cameraPermission.launch(Manifest.permission.CAMERA)},photo={attach=false;photoPicker.launch("image/*")},privateGallery={attach=false;chooseGallery=true},quickie={attach=false;quickiePicker=true})
    if(chooseGallery)PrivateGalleryPicker(vm,{chooseGallery=false}){gallery->chooseGallery=false;val userId=other?.strOrNull("userId")?:other?.strOrNull("id");if(userId==null)vm.error.value="This member could not be identified." else vm.runTask{ApiClient.request("private-galleries/${gallery.str("id")}/access",HttpMethod.Post,obj("userId" to userId));load()}}
    openedGallery?.let{SharedGalleryViewer(it,vm){openedGallery=null}}
    if(quickiePicker) AlertDialog(onDismissRequest={quickiePicker=false},title={Text("Quickie 😈")},text={Text("One look. Then it\u0027s gone.")},confirmButton={Row{TextButton(onClick={quickiePicker=false;quickiePhotoPicker.launch("image/*")}){Text("Photo")};TextButton(onClick={quickiePicker=false;quickieVideoPicker.launch("video/*")}){Text("Video")}}},dismissButton={TextButton(onClick={quickiePicker=false}){Text("Cancel")}})
    openedQuickie?.let { media -> QuickieViewer(media){openedQuickie=null} }
}
private fun replySnippet(m:JsonObject):String = when {
    m.bool("viewOnce") -> "Quickie 😈"
    m.str("kind").uppercase() in setOf("IMAGE","GIF") -> "Photo"
    m.str("kind").uppercase()=="VIDEO" -> "Video"
    m.str("body").startsWith("LOCATION:") -> "Shared location"
    else -> m.str("body").take(120)
}
@Composable private fun MessageBubble(m:JsonObject,me:String?,openGallery:(String)->Unit={},openOnce:(String)->Unit={},reply:(JsonObject)->Unit={}){
    val mine=m.str("senderId")==me
    val body=m.str("body")
    val kind=m.str("kind").uppercase()
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start){
        Column(Modifier.widthIn(max=300.dp).combinedClickable(onClick={},onLongClick={reply(m)}).background(if(mine)Electric.copy(alpha=.5f)else Raised,RoundedCornerShape(18.dp)).padding(12.dp)){
            m.strOrNull("replyPreview")?.let { preview ->
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha=.2f),RoundedCornerShape(8.dp)).padding(8.dp)){
                    Text("Reply",color=Lavender,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                    Text(preview,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
            }
            when {
                m.bool("viewOnce") -> Column(Modifier.clickable(enabled=!mine && !m.bool("opened")){openOnce(m.str("id"))},verticalArrangement=Arrangement.spacedBy(3.dp)){
                    Text("Quickie 😈",color=Lavender,fontWeight=FontWeight.Bold)
                    Text(if(mine)"Sent · One look. Then it's gone." else if(m.bool("opened"))"Already opened" else "Tap to view once",color=Color.White)
                }
                body.startsWith("LOCATION:") -> {
                    val context=LocalContext.current
                    val coords=body.removePrefix("LOCATION:").trim()
                    val parts=coords.split(",")
                    val valid=parts.size==2&&parts.all{it.trim().toDoubleOrNull()!=null}
                    Column(Modifier.clickable(enabled=valid){val uri=Uri.parse("geo:0,0?q=${Uri.encode(coords)}");runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,uri))}.onFailure{runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(coords)}")))}}}){
                        Text("📍 Shared location",fontWeight=FontWeight.Bold,color=Color.White)
                        Text(if(valid)"Tap to open in Maps" else "Location unavailable",color=Lavender)
                    }
                }
                kind=="IMAGE"||kind=="GIF" -> {
                    var fullScreen by remember(body){mutableStateOf(false)}
                    AsyncImage(authenticatedImageModel(body),"Chat image",Modifier.fillMaxWidth().heightIn(min=120.dp,max=320.dp).clip(RoundedCornerShape(12.dp)).clickable{fullScreen=true},contentScale=ContentScale.Fit)
                    if(fullScreen) androidx.compose.ui.window.Dialog(onDismissRequest={fullScreen=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
                        Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){
                            AsyncImage(authenticatedImageModel(body),"Full size chat image",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                            IconButton({fullScreen=false},Modifier.align(Alignment.TopEnd).padding(16.dp)){Icon(Icons.Default.Close,"Close image",tint=Color.White)}
                        }
                    }
                }
                kind=="VIDEO" -> {
                    var fullScreen by remember(body){mutableStateOf(false)}
                    Row(Modifier.clickable{fullScreen=true},verticalAlignment=Alignment.CenterVertically){
                        Icon(Icons.Default.PlayCircle,null,tint=Lavender)
                        Spacer(Modifier.width(8.dp))
                        Text("Tap to view video",color=Color.White)
                    }
                    if(fullScreen) androidx.compose.ui.window.Dialog(onDismissRequest={fullScreen=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
                        Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){
                            AndroidView(factory={ctx->VideoView(ctx).apply{
                                val headers=mutableMapOf("X-AFTRHRS-Client" to "AFTRHRS-Native-Android/1.0")
                                Network.cookieHeader().takeIf{it.isNotBlank()}?.let{headers["Cookie"]=it}
                                setVideoURI(Uri.parse(ApiClient.mediaUrl(body)),headers)
                                setOnPreparedListener{start()}
                                val player=this
                                setMediaController(android.widget.MediaController(ctx).apply{setAnchorView(player)})
                            }},modifier=Modifier.fillMaxWidth().height(420.dp))
                            IconButton({fullScreen=false},Modifier.align(Alignment.TopEnd).padding(16.dp)){Icon(Icons.Default.Close,"Close video",tint=Color.White)}
                        }
                    }
                }
                kind=="GALLERY"||body.startsWith("GALLERY_SHARE:") -> Column(Modifier.clickable{body.substringAfter("GALLERY_SHARE:").takeIf{it.isNotBlank()}?.let(openGallery)}){
                    Text("Private gallery shared",color=Color.White,fontWeight=FontWeight.SemiBold)
                    Text("Tap to open",color=Lavender,style=MaterialTheme.typography.labelSmall)
                }
                else -> SelectionContainer { ChatLinkPreview(body) }
            }
        }
    }
}
@Composable private fun QuickieViewer(media:Pair<ByteArray,String>,dismiss:()->Unit){
    val context=LocalContext.current
    val video=media.second.startsWith("video/")
    val temp=remember(media){if(video)File.createTempFile("quickie-",".mp4",context.cacheDir).apply{writeBytes(media.first)}else null}
    DisposableEffect(temp){onDispose{temp?.delete()}}
    androidx.compose.ui.window.Dialog(onDismissRequest=dismiss){
        Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(18.dp)).padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Text("Quickie 😈",color=Lavender,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if(video && temp!=null) AndroidView(factory={ctx->VideoView(ctx).apply{setVideoPath(temp.absolutePath);setOnPreparedListener{it.isLooping=false;start()}}},modifier=Modifier.fillMaxWidth().height(400.dp))
            else {
                val bitmap=remember(media){BitmapFactory.decodeByteArray(media.first,0,media.first.size)?.asImageBitmap()}
                if(bitmap!=null)Image(bitmap,"Quickie",Modifier.fillMaxWidth().heightIn(max=550.dp),contentScale=ContentScale.Fit)
                else Text("Unable to display this media.",color=Muted)
            }
            Text("One look. Then it's gone.",color=Muted)
            TextButton(onClick=dismiss){Text("Close")}
        }
    }
}

@Composable private fun ChatLinkPreview(body:String){
    val link=remember(body){chatLink(body)}
    if(link==null){Text(body,color=Color.White);return}
    val context=LocalContext.current
    var preview by remember(link){mutableStateOf<JsonObject?>(null)}
    var previewLoading by remember(link){mutableStateOf(true)}
    LaunchedEffect(link){
        previewLoading=true
        preview=runCatching{ApiClient.linkPreview(link)}.getOrNull()
        previewLoading=false
    }
    val parsed=remember(link){Uri.parse(link)}
    val hostname=parsed.host?.removePrefix("www.")?:link
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(body,color=Color.White)
        Row(Modifier.fillMaxWidth().border(1.dp,Lavender.copy(alpha=.4f),RoundedCornerShape(12.dp)).background(Panel.copy(alpha=.55f),RoundedCornerShape(12.dp)).clickable{runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,parsed))}}.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            val image=preview?.strOrNull("image")
            if(image!=null)AsyncImage(image,"",Modifier.size(68.dp).clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Crop)
            else Icon(Icons.Default.Link,null,tint=Lavender,modifier=Modifier.size(28.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(preview?.strOrNull("title")?:hostname,color=Color.White,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                preview?.strOrNull("description")?.takeIf{it.isNotBlank()}?.let{Text(it,color=Muted,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis)}
                if(previewLoading) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Lavender)
                else if(preview==null) Text("Preview unavailable",color=Muted,style=MaterialTheme.typography.labelSmall)
                Text(link,color=Muted,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("Open in browser ↗",color=Lavender,style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun SharedGalleryViewer(gallery:JsonObject,vm:AppViewModel,dismiss:()->Unit){
    val context=LocalContext.current
    val activity=context as? Activity
    val galleryId=gallery.str("id")
    val scope=rememberCoroutineScope()
    var captureHandled by remember(galleryId){mutableStateOf(false)}
    val onCapture=rememberUpdatedState {
        if(!captureHandled && galleryId.isNotBlank()){
            captureHandled=true
            dismiss()
            scope.launch {
                runCatching {
                    ApiClient.request("private-galleries/$galleryId/screenshot-revoke",HttpMethod.Post,
                        obj("reason" to "ANDROID_CAPTURE_DETECTED"))
                }.onFailure { vm.error.value="Gallery closed. Could not confirm access revocation; please check your connection." }
            }
        }
    }
    // Detection is best-effort: FLAG_SECURE remains enabled and can suppress
    // screenshot callbacks. Never disable capture prevention to force detection.
    DisposableEffect(activity,galleryId) {
        if(activity==null || galleryId.isBlank()) return@DisposableEffect onDispose {}
        val executor=Executor { task -> Handler(Looper.getMainLooper()).post(task) }
        val screenshotCallback=if(Build.VERSION.SDK_INT>=34) {
            val callback=Activity.ScreenCaptureCallback { onCapture.value.invoke() }
            runCatching { activity.registerScreenCaptureCallback(executor,callback) }.getOrNull()?.let { callback }
        } else null
        val windowManager=activity.getSystemService(WindowManager::class.java)
        val recordingCallback=if(Build.VERSION.SDK_INT>=35 && windowManager!=null) {
            val callback=Consumer<Int> { state ->
                if(state==SCREEN_RECORDING_STATE_VISIBLE) onCapture.value.invoke()
            }
            runCatching { windowManager.addScreenRecordingCallback(executor,callback) }.getOrNull()?.let { callback }
        } else null
        onDispose {
            if(Build.VERSION.SDK_INT>=34 && screenshotCallback!=null) runCatching { activity.unregisterScreenCaptureCallback(screenshotCallback) }
            if(Build.VERSION.SDK_INT>=35 && recordingCallback!=null) runCatching { windowManager?.removeScreenRecordingCallback(recordingCallback) }
        }
    }
ModalBottomSheet(onDismissRequest=dismiss,containerColor=Panel){Column(Modifier.fillMaxWidth().heightIn(max=800.dp).padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(gallery.strOrNull("title")?:"Private gallery",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White);val photos=gallery.array("photos");if(photos.isEmpty())Box(Modifier.fillMaxWidth().height(240.dp),contentAlignment=Alignment.Center){Text("This gallery has no photos.",color=Muted)}else LazyColumn(Modifier.heightIn(max=620.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(photos.size){index->AsyncImage(authenticatedImageModel(photos[index].jsonObject.strOrNull("url")),"Gallery photo",Modifier.fillMaxWidth().heightIn(min=260.dp,max=560.dp).clip(RoundedCornerShape(18.dp)).background(Raised),contentScale=ContentScale.Fit)}};OutlinedButton(dismiss,Modifier.fillMaxWidth()){Text("Done")};Spacer(Modifier.height(20.dp))}}}

@Composable private fun AttachmentSheet(dismiss:()->Unit,vm:AppViewModel,send:(String)->Unit,camera:()->Unit,photo:()->Unit,privateGallery:()->Unit,quickie:()->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    fun shareLocation(){scope.launch{runCatching{currentLocation(context)}.onSuccess{send("LOCATION:${it.first},${it.second}");dismiss()}.onFailure{vm.error.value=it.message?:"Location could not be shared."}}}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->if(result.values.any{it})shareLocation()else vm.error.value="Location permission is required to share your location."}
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=Panel){Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Share",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        ListItem(headlineContent={Text("Share current location")},leadingContent={Icon(Icons.Default.LocationOn,null)},modifier=Modifier.clickable{if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)shareLocation()else request.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))})
        ListItem(headlineContent={Text("Camera")},supportingContent={Text("Take a new photo")},leadingContent={Icon(Icons.Default.PhotoCamera,null)},modifier=Modifier.clickable(onClick=camera))
        ListItem(headlineContent={Text("Photo Gallery")},supportingContent={Text("Choose an existing photo")},leadingContent={Icon(Icons.Default.PhotoLibrary,null)},modifier=Modifier.clickable(onClick=photo))
        ListItem(headlineContent={Text("Quickie 😈")},supportingContent={Text("One look. Then it\u0027s gone.")},leadingContent={Icon(Icons.Default.LocalFireDepartment,null)},modifier=Modifier.clickable(onClick=quickie))
        ListItem(headlineContent={Text("Private Gallery")},leadingContent={Icon(Icons.Default.Collections,null)},modifier=Modifier.clickable(onClick=privateGallery))
        Spacer(Modifier.height(14.dp))
    }}
}
private suspend fun uploadFileFromUri(context:Context,uri:Uri,path:String,field:String="image"){
    val mime=context.contentResolver.getType(uri)?.lowercase()?.substringBefore(";").orEmpty()
    val extension=android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
    val type=when {
        mime in setOf("video/mp4","video/quicktime","video/webm") -> mime
        mime in setOf("image/jpeg","image/jpg","image/png","image/webp") -> mime
        mime=="application/octet-stream" || mime.isBlank() -> when(extension){
            "mp4"->"video/mp4";"mov"->"video/quicktime";"webm"->"video/webm"
            "jpg","jpeg"->"image/jpeg";"png"->"image/png";"webp"->"image/webp"
            else->""
        }
        else -> ""
    }
    if(type.isBlank()) error("This media format is not supported. Choose a JPEG, PNG, WebP, MP4, MOV or WebM file.")
    val ext=when(type) {
        "video/mp4"->"mp4";"video/quicktime"->"mov";"video/webm"->"webm"
        "image/png"->"png";"image/webp"->"webp";else->"jpg"
    }
    val file=File.createTempFile("aftrhrs-upload-","."+ext,context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("The selected media could not be read.")
        if(file.length()==0L) error("The selected media is empty.")
        if(path.endsWith("/view-once")){
            val maxBytes=if(type.startsWith("video/"))30L*1024*1024 else 8L*1024*1024
            if(file.length()>maxBytes) error("Quickie is too large. Maximum ${if(type.startsWith("video/"))"30 MB for video" else "8 MB for photos"}.")
        }
        ApiClient.upload(path,file,field)
    } finally { file.delete() }
}
@Composable private fun PrivateGalleryPicker(vm:AppViewModel,dismiss:()->Unit,select:(JsonObject)->Unit){var galleries by remember{mutableStateOf<JsonArray?>(null)};LaunchedEffect(Unit){vm.runTask{val response=ApiClient.request("private-galleries");galleries=if(response is JsonArray)response else response.jsonObject.array("items")}};AlertDialog(dismiss,title={Text("Share a private gallery")},text={LazyColumn(Modifier.heightIn(max=420.dp)){val items=galleries;if(items==null)item{CircularProgressIndicator()}else if(items.isEmpty())item{Text("You do not have a private gallery to share yet.",color=Muted)}else items(items.size){index->val gallery=items[index].jsonObject;ListItem(headlineContent={Text(gallery.strOrNull("title")?:gallery.strOrNull("name")?:"Private gallery")},supportingContent={Text("${gallery.array("photos").size} photos")},leadingContent={Icon(Icons.Default.Collections,null,tint=Lavender)},modifier=Modifier.clickable{select(gallery)})}}},confirmButton={TextButton(dismiss){Text("Cancel")}})}
private suspend fun currentLocation(context:android.content.Context):Pair<Double,Double> = suspendCancellableCoroutine{cont->val client=LocationServices.getFusedLocationProviderClient(context);try{client.lastLocation.addOnSuccessListener{loc->if(loc!=null)cont.resume(loc.latitude to loc.longitude)else cont.resumeWithException(Exception("Current location is unavailable. Try again outdoors."))}.addOnFailureListener{cont.resumeWithException(it)}}catch(e:SecurityException){cont.resumeWithException(e)}}

private val groupBubblePalette = listOf(
    Color(0xFF5A327A), Color(0xFF285C68), Color(0xFF704055),
    Color(0xFF4A5D35), Color(0xFF68502D), Color(0xFF345A88),
    Color(0xFF714B79), Color(0xFF38665B), Color(0xFF79513F),
    Color(0xFF46517B), Color(0xFF5B6340), Color(0xFF70405F)
)

@Composable private fun GroupChatScreen(vm:AppViewModel,g:JsonObject,back:()->Unit){
    BackHandler(onBack=back)
    val id=g.str("id")
    val me=vm.user.value?.str("id")
    var messages by remember(id){mutableStateOf<JsonArray>(JsonArray(emptyList()))}
    var text by remember(id){mutableStateOf("")}
    var replyingTo by remember(id){mutableStateOf<JsonObject?>(null)}
    var openedGallery by remember{mutableStateOf<JsonObject?>(null)}
    var showGroupMenu by remember{mutableStateOf(false)}
    var groupTitle by remember(id){mutableStateOf(g.str("title"))}
    var groupPhoto by remember(id){mutableStateOf(g.strOrNull("photoUrl"))}
    var editingGroup by remember{mutableStateOf(false)}
    var proposedTitle by remember{mutableStateOf(g.str("title"))}
    var savingGroup by remember{mutableStateOf(false)}
    val context=LocalContext.current
    val groupPhotoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
        if(uri!=null)vm.runTask{
            savingGroup=true
            try{
                uploadFileFromUri(context,uri,"group-chats/$id/photo","photo")
                val latest=ApiClient.request("group-chats").jsonArray.firstOrNull{it.jsonObject.str("id")==id}?.jsonObject
                groupPhoto=latest?.strOrNull("photoUrl")
            }finally{savingGroup=false}
        }
    }
    var confirmLeave by remember{mutableStateOf(false)}
    var leaving by remember{mutableStateOf(false)}
    val listState=rememberLazyListState()
    suspend fun load(){messages=ApiClient.request("group-chats/$id/messages").jsonArray}
    LaunchedEffect(id){if(g.str("status")=="ACCEPTED")vm.runTask{load()}}
    LaunchedEffect(messages.size){if(messages.isNotEmpty())listState.scrollToItem(messages.lastIndex)}
    val participants=remember(messages,g){
        (g.array("members").map{it.jsonObject.str("id")}+
            messages.map{it.jsonObject.str("senderId")}).filter{it.isNotBlank()}.distinct().sorted()
    }
    val memberColors=remember(participants){
        participants.mapIndexed{index,userId->userId to groupBubblePalette[index%groupBubblePalette.size]}.toMap()
    }
    Column(Modifier.fillMaxSize().imePadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(back){Icon(Icons.Default.ArrowBack,"Back")}
            if(!groupPhoto.isNullOrBlank())Avatar(groupPhoto,Modifier.size(42.dp).clickable{if(g.str("status")=="ACCEPTED")editingGroup=true})
            else Icon(Icons.Default.Groups,null,tint=Lavender,modifier=Modifier.size(42.dp).clickable{if(g.str("status")=="ACCEPTED")editingGroup=true})
            Spacer(Modifier.width(8.dp))
            Text(groupTitle,Modifier.weight(1f).clickable{if(g.str("status")=="ACCEPTED")editingGroup=true},color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge,maxLines=1)
            Box{
                IconButton({showGroupMenu=true}){Icon(Icons.Default.Groups,"Group options",tint=Lavender)}
                DropdownMenu(expanded=showGroupMenu,onDismissRequest={showGroupMenu=false}){
                    if(g.str("status")=="ACCEPTED")DropdownMenuItem(text={Text("Edit group name and photo")},onClick={showGroupMenu=false;proposedTitle=groupTitle;editingGroup=true})
                    DropdownMenuItem(text={Text("Leave group",color=Color(0xFFFF777E))},onClick={showGroupMenu=false;confirmLeave=true})
                }
            }
        }
        if(g.str("status")=="PENDING"){
            Row(Modifier.padding(12.dp).glassCard()){
                ElectricButton({vm.runTask{ApiClient.request("group-chats/$id/respond",HttpMethod.Post,obj("action" to "accept"));back()}},Modifier.weight(1f)){Text("Accept")}
                Spacer(Modifier.width(8.dp))
                OutlinedButton({vm.runTask{ApiClient.request("group-chats/$id/respond",HttpMethod.Post,obj("action" to "decline"));back()}},Modifier.weight(1f)){Text("Decline")}
            }
        }else{
            LazyColumn(Modifier.weight(1f),state=listState,contentPadding=PaddingValues(12.dp)){
                items(messages.size){i->
                    val m=messages[i].jsonObject
                    val sender=m.str("senderId")
                    val mine=sender==me
                    val bubbleColor=memberColors[sender]?:Raised
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start){
                        Column(Modifier.widthIn(max=300.dp)){
                            Text(m.str("senderName"),color=bubbleColor.copy(alpha=1f),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                            Column(Modifier.fillMaxWidth().combinedClickable(onClick={},onLongClick={replyingTo=m}).background(bubbleColor,RoundedCornerShape(18.dp)).padding(12.dp)){
                                m.strOrNull("replyPreview")?.let{preview->
                                    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha=.2f),RoundedCornerShape(8.dp)).padding(8.dp)){
                                        Text("Reply",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall,color=Color.White)
                                        Text(preview,maxLines=2,overflow=TextOverflow.Ellipsis,color=Color.White)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                when{
                                    m.bool("viewOnce")->Text(if(mine)"Quickie 😈 sent" else "Quickie 😈 · View once",color=Color.White)
                                    m.str("kind") in setOf("IMAGE","GIF")->AsyncImage(authenticatedImageModel(m.strOrNull("body")),"Group photo",Modifier.fillMaxWidth().height(220.dp),contentScale=ContentScale.Fit)
                                    else->SelectionContainer { Text(m.str("body"),color=Color.White) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            replyingTo?.let{original->
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).background(Raised,RoundedCornerShape(12.dp)).padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text("Replying to ${original.str("senderName")}",color=Lavender,fontWeight=FontWeight.Bold)
                        Text(replySnippet(original),maxLines=2,overflow=TextOverflow.Ellipsis,color=Color.White)
                    }
                    IconButton({replyingTo=null}){Icon(Icons.Default.Close,"Cancel reply")}
                }
            }
            Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                OutlinedTextField(text,{text=it},Modifier.weight(1f),placeholder={Text("Message")})
                IconButton({
                    val body=text.trim()
                    if(body.isNotBlank()){
                        val target=replyingTo?.strOrNull("id")
                        text="";replyingTo=null
                        vm.runTask{
                            ApiClient.request("group-chats/$id/messages",HttpMethod.Post,buildJsonObject{
                                put("body",body)
                                if(target!=null)put("replyToId",target)
                            })
                            load()
                        }
                    }
                }){Icon(Icons.Default.Send,"Send")}
            }
        }
    }
    if(editingGroup)AlertDialog(onDismissRequest={if(!savingGroup)editingGroup=false},
        title={Text("Edit group")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(proposedTitle,{proposedTitle=it.take(60)},label={Text("Group name")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                if(!groupPhoto.isNullOrBlank())Avatar(groupPhoto,Modifier.size(58.dp))
                else Icon(Icons.Default.Groups,null,tint=Lavender,modifier=Modifier.size(58.dp))
                OutlinedButton(onClick={groupPhotoPicker.launch("image/*")},enabled=!savingGroup){Text("Change picture")}
            }
            Text("All accepted participants can change the group name and picture.",color=Muted,style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(onClick={vm.runTask{
            savingGroup=true
            try{
                val updated=ApiClient.request("group-chats/$id",HttpMethod.Patch,obj("title" to proposedTitle.trim())).jsonObject
                groupTitle=updated.str("title")
                editingGroup=false
            }finally{savingGroup=false}
        }},enabled=!savingGroup&&proposedTitle.trim().length in 2..60){Text("Save")}},
        dismissButton={TextButton({editingGroup=false},enabled=!savingGroup){Text("Cancel")}}
    )
    if(confirmLeave)AlertDialog(onDismissRequest={if(!leaving)confirmLeave=false},title={Text("Leave group?")},text={Text("You will leave this group chat and stop receiving its messages.")},dismissButton={TextButton({confirmLeave=false},enabled=!leaving){Text("Cancel")}},confirmButton={TextButton({if(!leaving){leaving=true;vm.runTask{try{ApiClient.request("group-chats/$id/leave",HttpMethod.Post);confirmLeave=false;back()}finally{leaving=false}}}},enabled=!leaving){Text(if(leaving)"Leaving..." else "Leave group",color=Color(0xFFFF777E))}})
    openedGallery?.let{SharedGalleryViewer(it,vm){openedGallery=null}}
}

@Composable fun PremiumScreen(vm:AppViewModel){var sub by remember{mutableStateOf<JsonObject?>(null)};var pendingChange by remember{mutableStateOf<String?>(null)};var changing by remember{mutableStateOf(false)};val premium=(sub?.str("tier")?:vm.user.value?.str("subscription"))=="PREMIUM";LaunchedEffect(Unit){vm.runTask{sub=ApiClient.request("subscription").jsonObject}};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){Header("Premium","Own the night");Column(Modifier.padding(18.dp).glassCard()){Icon(Icons.Default.Diamond,null,tint=Lavender,modifier=Modifier.size(54.dp));Text(if(premium)"YOU'RE PREMIUM"else"PREMIUM",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(if(premium)"Every Premium feature is unlocked."else"Wider discovery, advanced filters and read receipts.",color=Muted,modifier=Modifier.padding(vertical=10.dp));listOf("See more people across a larger radius","Use advanced discovery filters","Know when your messages are read","Support an independent, adults-only community").forEach{Row(Modifier.padding(vertical=6.dp)){Icon(Icons.Default.CheckCircle,null,tint=HotPink);Spacer(Modifier.width(8.dp));Text(it)}};Spacer(Modifier.height(16.dp));NeonButton(if(premium)"Manage membership"else"Upgrade to Premium",!changing){pendingChange=if(premium)"downgrade"else"upgrade"}};Text("Payments in this build use the server’s demo subscription flow. Google Play Billing must be configured before public sale.",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(24.dp))};pendingChange?.let{change->AlertDialog(onDismissRequest={pendingChange=null},title={Text(if(change=="upgrade")"Upgrade to Premium?"else"Manage membership")},text={Text(if(change=="upgrade")"Unlock Premium features for your AFTRHRS account."else"Current plan: Premium. Downgrading restores the 25 km limit and turns off Premium-only controls.")},dismissButton={TextButton({pendingChange=null}){Text("Cancel")}},confirmButton={ElectricButton(onClick={pendingChange=null;changing=true;vm.runTask{try{val result=ApiClient.request("subscription/demo-$change",HttpMethod.Post).jsonObject;sub=ApiClient.request("subscription").jsonObject;vm.refresh();vm.error.value=result.strOrNull("warning")?:if(change=="upgrade")"Premium activated."else"Your plan is now Free."}finally{changing=false}}}){Text(if(change=="upgrade")"Upgrade"else"Downgrade to Free")}})}}

@Composable fun MeScreen(vm:AppViewModel,lockEnabled:Boolean,setLock:(Boolean)->Unit){val context=LocalContext.current;fun openLegal(url:String){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }.onFailure{vm.error.value="Unable to open this page."}};val u=vm.user.value;val profile=u?.objectOrNull("profile");val isAdmin=u?.str("role") in setOf("ADMIN","SUPER_ADMIN");var editor by remember{mutableStateOf(false)};var galleries by remember{mutableStateOf(false)};var partners by remember{mutableStateOf(false)};var appIcons by remember{mutableStateOf(false)};var privacy by remember{mutableStateOf(false)};var profileViews by remember{mutableStateOf(false)};var adminPanel by remember{mutableStateOf(false)};var selectedProfile by remember{mutableStateOf<String?>(null)};selectedProfile?.let{ProfileScreen(vm,it){selectedProfile=null};return};if(profileViews){ProfileViewsScreen(vm,{profileViews=false}){id->profileViews=false;selectedProfile=id};return};if(adminPanel&&isAdmin){AdminReviewScreen(vm){adminPanel=false};return};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){Header("Me","Your profile and privacy");Column(Modifier.padding(horizontal=18.dp).glassCard()){Row(verticalAlignment=Alignment.CenterVertically){Avatar(u?.array("photos")?.firstOrNull()?.let{it as? JsonObject}?.strOrNull("url"),Modifier.size(82.dp));Spacer(Modifier.width(14.dp));Column{Text(profile?.str("displayName")?.takeIf{it.isNotBlank()}?:"AFTRHRS member",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color.White);Text(u?.str("email").orEmpty(),color=Muted);Text(u?.str("verification").orEmpty(),color=Lavender)}}};SettingsItem(Icons.Default.Edit,"Edit profile","Photos, bio, identity and interests"){editor=true};SettingsItem(Icons.Default.Visibility,"Profile views",if(u?.str("subscription")=="PREMIUM")"See visitors from the last 7 days"else"See your 4 most recent visitors"){profileViews=true};if(isAdmin)SettingsItem(Icons.Default.AdminPanelSettings,"Admin reviews","Approve or reject photos and identity submissions"){adminPanel=true};SettingsItem(Icons.Default.Collections,"Shared galleries","Manage private photo access"){galleries=true};SettingsItem(Icons.Default.Lock,"App Lock",if(lockEnabled)"On" else"Off"){setLock(!lockEnabled)};SettingsItem(Icons.Default.Apps,"Discreet app icon",if(u?.str("subscription")=="PREMIUM")"Choose your home-screen icon"else"Premium feature"){appIcons=true};SettingsItem(Icons.Default.Link,"Linked partners","Invite, confirm and manage partners"){partners=true};SettingsItem(Icons.Default.PrivacyTip,"Privacy settings","Visibility, requests, receipts and discovery"){privacy=true};SettingsItem(Icons.Default.Description,"Privacy Policy","aftrhrs.co.za/privacy"){openLegal("https://aftrhrs.co.za/privacy")};SettingsItem(Icons.Default.Description,"End User Licence Agreement","aftrhrs.co.za/eula"){openLegal("https://aftrhrs.co.za/eula")};SettingsItem(Icons.Default.Logout,"Sign out",null){vm.logout()};Spacer(Modifier.height(24.dp))};if(editor)ProfileEditor(vm){editor=false};if(galleries)SharedGalleries(vm){galleries=false};if(partners)LinkedPartners(vm,{partners=false}){id->partners=false;selectedProfile=id};if(appIcons)AppIconPicker(u?.str("subscription")=="PREMIUM"){appIcons=false};if(privacy)PrivacySettings(vm){privacy=false}}

private fun profileViewTime(value:String?):String?=value?.let{runCatching{DateUtils.getRelativeTimeSpanString(Instant.parse(it).toEpochMilli(),System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS).toString()}.getOrNull()}

@Composable private fun ProfileViewsScreen(vm:AppViewModel,dismiss:()->Unit,openProfile:(String)->Unit){
    var response by remember{mutableStateOf<JsonObject?>(null)}
    var loading by remember{mutableStateOf(true)}
    fun load(){vm.runTask{loading=true;try{response=ApiClient.request("profile-views").jsonObject}finally{loading=false}}}
    LaunchedEffect(Unit){load()}
    val premium=response?.bool("premium")==true
    val visitors=(response?.array("visitors")?:JsonArray(emptyList())).map{it.jsonObject}.let{if(premium)it else it.take(4)}
    BackHandler(onBack=dismiss)
    Column(Modifier.fillMaxSize()){
        Header("Profile views",if(premium)"Visitors from the last 7 days, newest first."else"Your 4 most recent profile views, newest first."){IconButton(dismiss){Icon(Icons.Default.Close,"Back")}}
        when{
            loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=Electric)}
            visitors.isEmpty()->Box(Modifier.fillMaxSize().padding(28.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.VisibilityOff,null,tint=Muted,modifier=Modifier.size(56.dp));Text("No profile views yet",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("When someone visits your profile, they'll appear here.",color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
            else->LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=14.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                items(visitors.chunked(2)){row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){row.forEach{visitor->val anonymous=visitor.bool("anonymous")||visitor.strOrNull("id")==null;Column(Modifier.weight(1f).glassCard(10).then(if(anonymous)Modifier else Modifier.clickable{visitor.strOrNull("id")?.let(openProfile)}),verticalArrangement=Arrangement.spacedBy(8.dp)){Box{Avatar(visitor.strOrNull("photo"),Modifier.fillMaxWidth().aspectRatio(1f));Icon(Icons.Default.Visibility,null,tint=Color.White,modifier=Modifier.align(Alignment.TopEnd).padding(7.dp).size(18.dp))};Text(visitor.strOrNull("name")?:"Private visitor",fontWeight=FontWeight.Bold,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis);Text(profileViewTime(visitor.strOrNull("viewedAt"))?:"Identity hidden",color=Muted,style=MaterialTheme.typography.bodySmall)}};if(row.size==1)Spacer(Modifier.weight(1f))}}
                if(!premium)item{Column(Modifier.fillMaxWidth().glassCard(),verticalArrangement=Arrangement.spacedBy(7.dp)){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Icon(Icons.Default.AutoAwesome,null,tint=Lavender);Text("Premium remembers more",fontWeight=FontWeight.Bold,color=Lavender)};Text("Upgrade to see profile visitors from the full last 7 days. Visits made using Private Browsing remain anonymous.",color=Muted)}}
                item{Spacer(Modifier.height(18.dp))}
            }
        }
    }
}

@Composable private fun AdminReviewScreen(vm:AppViewModel,dismiss:()->Unit){
    var tab by remember{mutableIntStateOf(0)}
    var photos by remember{mutableStateOf(JsonArray(emptyList()))}
    var identities by remember{mutableStateOf<List<JsonObject>>(emptyList())}
    var loading by remember{mutableStateOf(true)}
    var reviewPhoto by remember{mutableStateOf<JsonObject?>(null)}
    var reviewIdentity by remember{mutableStateOf<JsonObject?>(null)}
    var reviewBusy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)}
    fun load(){vm.runTask{loading=true;try{photos=ApiClient.request("admin/profile-photos").jsonArray;identities=ApiClient.request("admin/users").jsonArray.map{it.jsonObject}.filter{it.objectOrNull("verification")?.str("status") in setOf("PENDING","REVIEW")}}finally{loading=false}}}
    LaunchedEffect(Unit){load()}
    BackHandler(onBack=dismiss)
    Column(Modifier.fillMaxSize()){
        Header("Admin reviews","Role-protected moderation queues"){IconButton(dismiss){Icon(Icons.Default.Close,"Back")}}
        TabRow(tab){Tab(tab==0,{tab=0},text={Text("Photos (${photos.size})")});Tab(tab==1,{tab=1},text={Text("Identity (${identities.size})")})}
        PullToRefreshBox(loading,{load()},Modifier.weight(1f)){
            if(loading&&photos.isEmpty()&&identities.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=Electric)}
            else if(tab==0)AdminPhotoQueue(photos,select={reviewPhoto=it})
            else AdminIdentityQueue(identities,select={reviewIdentity=it})
        }
    }
    reviewPhoto?.let{photo->PhotoReviewDialog(photo,reviewBusy,{reviewPhoto=null}){status,note->reviewBusy=true;vm.runTask{try{ApiClient.request("admin/profile-photos/${photo.str("id")}",HttpMethod.Patch,buildJsonObject{put("status",status);put("note",note)});reviewPhoto=null;message="Profile photo ${status.lowercase()}.";load()}finally{reviewBusy=false}}}}
    reviewIdentity?.let{member->IdentityReviewDialog(member,reviewBusy,{reviewIdentity=null}){status,note,manualResolution->reviewBusy=true;vm.runTask{try{ApiClient.request("admin/users/${member.str("id")}/verification",HttpMethod.Patch,buildJsonObject{put("status",status);put("note",note);put("manualResolution",manualResolution)});reviewIdentity=null;message="Identity submission ${status.lowercase()}.";load()}finally{reviewBusy=false}}}}
    message?.let{AlertDialog(onDismissRequest={message=null},confirmButton={TextButton({message=null}){Text("OK")}},title={Text("Admin review")},text={Text(it)})}
}

@Composable private fun AdminPhotoQueue(photos:JsonArray,select:(JsonObject)->Unit){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){if(photos.isEmpty())item{AdminEmptyState(Icons.Default.PhotoLibrary,"No profile photos are waiting for review.")};items(photos.size){index->val photo=photos[index].jsonObject;val user=photo.objectOrNull("user");Column(Modifier.fillMaxWidth().glassCard(),verticalArrangement=Arrangement.spacedBy(10.dp)){AsyncImage(authenticatedImageModel(photo.strOrNull("url")),"Pending profile photo",Modifier.fillMaxWidth().heightIn(min=220.dp,max=430.dp).clip(RoundedCornerShape(18.dp)).clickable{select(photo)},contentScale=ContentScale.Crop);Text(user?.objectOrNull("profile")?.strOrNull("displayName")?:user?.strOrNull("email")?:"Member",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium);profileViewTime(photo.strOrNull("createdAt"))?.let{Text("Submitted $it",color=Muted)};ElectricButton({select(photo)},Modifier.fillMaxWidth()){Text("Review photo")}}}}}

@Composable private fun AdminIdentityQueue(members:List<JsonObject>,select:(JsonObject)->Unit){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){if(members.isEmpty())item{AdminEmptyState(Icons.Default.Badge,"No identity submissions are waiting for review.")};items(members.size){index->val member=members[index];val verification=member.objectOrNull("verification");val evidence=member.objectOrNull("_count")?.int("verificationEvidence")?:0;Column(Modifier.fillMaxWidth().glassCard(),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(member.objectOrNull("profile")?.strOrNull("displayName")?:member.strOrNull("email")?:"Member",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium);member.strOrNull("email")?.let{Text(it,color=Muted)};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){AssistChip({},label={Text(verification?.str("status")?:"PENDING")});AssistChip({},label={Text("$evidence / 3 images")})};profileViewTime(verification?.strOrNull("submittedAt"))?.let{Text("Submitted $it",color=Muted,style=MaterialTheme.typography.bodySmall)};ElectricButton({select(member)},Modifier.fillMaxWidth(),enabled=evidence==3){Text(if(evidence==3)"Review identity"else"Waiting for all evidence")}}}}}

@Composable private fun AdminEmptyState(icon:androidx.compose.ui.graphics.vector.ImageVector,text:String){Box(Modifier.fillMaxWidth().padding(vertical=70.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Icon(icon,null,tint=Muted,modifier=Modifier.size(58.dp));Text(text,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}}

@Composable private fun PhotoReviewDialog(photo:JsonObject,busy:Boolean,dismiss:()->Unit,decide:(String,String)->Unit){var note by remember(photo){mutableStateOf("")};AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text("Profile photo review")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){AsyncImage(authenticatedImageModel(photo.strOrNull("url")),"Profile photo awaiting moderation",Modifier.fillMaxWidth().heightIn(min=280.dp,max=470.dp).clip(RoundedCornerShape(16.dp)),contentScale=ContentScale.Fit);OutlinedTextField(note,{note=it.take(500)},label={Text("Reason if rejecting")},minLines=3,modifier=Modifier.fillMaxWidth())}},dismissButton={OutlinedButton({decide("REJECTED",note.trim())},enabled=!busy&&note.isNotBlank()){Text("Reject")}},confirmButton={ElectricButton({decide("APPROVED",note.trim())},enabled=!busy){Text(if(busy)"Saving…"else"Approve")}})}

@Composable private fun IdentityReviewDialog(member:JsonObject,busy:Boolean,dismiss:()->Unit,decide:(String,String,Boolean)->Unit){
    var note by remember(member){mutableStateOf("")}
    var manualResolution by remember(member){mutableStateOf(false)}
    val id=member.str("id")
    AlertDialog(
        onDismissRequest={if(!busy)dismiss()},
        title={Text("Identity review")},
        text={Column(Modifier.heightIn(max=610.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(member.objectOrNull("profile")?.strOrNull("displayName")?:member.strOrNull("email")?:"Member",fontWeight=FontWeight.Bold)
            listOf("id-front" to "ID front","id-back" to "ID back","selfie-with-id" to "Selfie holding ID").forEach{(slug,label)->
                Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
                    AsyncImage(authenticatedImageModel("/api/v1/admin/verifications/$id/evidence/$slug"),label,Modifier.fillMaxWidth().heightIn(min=190.dp,max=360.dp).clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Fit)
                    Text(label,color=Lavender,fontWeight=FontWeight.SemiBold)
                }
            }
            Text("Confirm age and identity from the original evidence before deciding.",color=Muted,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().clickable(enabled=!busy){manualResolution=!manualResolution},verticalAlignment=Alignment.CenterVertically){
                Checkbox(checked=manualResolution,onCheckedChange={manualResolution=it},enabled=!busy)
                Column(Modifier.weight(1f)){
                    Text("Manual resolution of AI discrepancy",fontWeight=FontWeight.SemiBold)
                    Text("Use only when the ID evidence confirms the registered date of birth and the member is 18 or older. Your decision is audited.",color=Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(note,{note=it.take(500)},label={Text(if(manualResolution)"Reviewer justification (at least 20 characters)"else"Reason if rejecting")},minLines=3,modifier=Modifier.fillMaxWidth())
            if(manualResolution&&note.trim().length<20)Text("Manual resolution requires a reviewer note of at least 20 characters.",color=Lavender,style=MaterialTheme.typography.bodySmall)
        }},
        dismissButton={OutlinedButton({decide("REJECTED",note.trim(),false)},enabled=!busy&&note.isNotBlank()){Text("Reject")}},
        confirmButton={ElectricButton({decide("APPROVED",note.trim(),manualResolution)},enabled=!busy&&(!manualResolution||note.trim().length>=20)){Text(if(busy)"Saving…"else if(manualResolution)"Approve with manual resolution"else"Approve")}}
    )
}
@Composable private fun SettingsItem(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,subtitle:String?,action:()->Unit){ListItem(headlineContent={Text(title,fontWeight=FontWeight.SemiBold)},supportingContent={subtitle?.let{Text(it)}},leadingContent={Icon(icon,null,tint=Lavender)},trailingContent={Icon(Icons.Default.ChevronRight,null)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.padding(horizontal=10.dp).clickable(onClick=action))}

private data class LauncherIconChoice(val id:String,val title:String,val component:String,val resource:Int)
@Composable private fun AppIconPicker(isPremium:Boolean,dismiss:()->Unit){val context=LocalContext.current;val choices=listOf(LauncherIconChoice("default","AFTRDRK","DefaultIcon",R.drawable.app_icon_default),LauncherIconChoice("eclipse","Eclipse","EclipseIcon",R.drawable.app_icon_eclipse),LauncherIconChoice("midnight","Midnight","MidnightIcon",R.drawable.app_icon_midnight),LauncherIconChoice("aurora","Aurora","AuroraIcon",R.drawable.app_icon_aurora),LauncherIconChoice("prism","Prism","PrismIcon",R.drawable.app_icon_prism),LauncherIconChoice("orbit","Orbit","OrbitIcon",R.drawable.app_icon_orbit));val preferences=remember{context.getSharedPreferences("launcher_icon",Context.MODE_PRIVATE)};var selected by remember{mutableStateOf(preferences.getString("selected","default")?:"default")};fun select(choice:LauncherIconChoice){if(!isPremium)return;val manager=context.packageManager;val packageName=context.packageName;val selectedComponent=ComponentName(packageName,"za.co.drkflm.afterhours.launcher.${choice.component}");manager.setComponentEnabledSetting(selectedComponent,PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP);choices.filter{it.id!=choice.id}.forEach{other->manager.setComponentEnabledSetting(ComponentName(packageName,"za.co.drkflm.afterhours.launcher.${other.component}"),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)};preferences.edit().putString("selected",choice.id).apply();selected=choice.id};ModalBottomSheet(onDismissRequest=dismiss,containerColor=Panel){Column(Modifier.fillMaxWidth().heightIn(max=740.dp).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("App icon",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White);if(!isPremium){Box(Modifier.fillMaxWidth().padding(vertical=54.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.Apps,null,tint=Lavender,modifier=Modifier.size(56.dp));Text("Premium icon styles",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("Upgrade to Premium to choose from five discreet AFTRHRS icons.",color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}}else{Text("Choose a discreet look for AFTRHRS on your Home Screen. Some launchers may take a few moments to refresh.",color=Muted);LazyColumn(Modifier.heightIn(max=510.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(choices.chunked(2)){row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){row.forEach{choice->Column(Modifier.weight(1f).glassCard(12).clickable{select(choice)},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){Image(painterResource(choice.resource),choice.title,Modifier.size(112.dp).clip(RoundedCornerShape(24.dp)),contentScale=ContentScale.Crop);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Text(choice.title,fontWeight=FontWeight.SemiBold,color=Color.White);if(selected==choice.id)Icon(Icons.Default.CheckCircle,"Selected",tint=Lavender,modifier=Modifier.size(18.dp))}}};if(row.size==1)Spacer(Modifier.weight(1f))}}}};OutlinedButton(dismiss,Modifier.fillMaxWidth()){Text("Done")};Spacer(Modifier.height(24.dp))}}}

@Composable private fun LinkedPartners(vm:AppViewModel,dismiss:()->Unit,openProfile:(String)->Unit){var links by remember{mutableStateOf<JsonArray?>(null)};var email by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};fun load(){vm.runTask{links=ApiClient.request("partners").jsonArray}};fun remove(link:JsonObject){vm.runTask{ApiClient.request("partners/${link.str("id")}",HttpMethod.Delete);links=ApiClient.request("partners").jsonArray}};fun respond(link:JsonObject,accept:Boolean){vm.runTask{ApiClient.request("partners/${link.str("id")}/respond",HttpMethod.Post,obj("accept" to accept));links=ApiClient.request("partners").jsonArray}};LaunchedEffect(Unit){load()};ModalBottomSheet(onDismissRequest=dismiss,containerColor=Panel){Column(Modifier.fillMaxWidth().heightIn(max=760.dp).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("Linked partners",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White);Text("Invite a partner using the email address registered to their AFTRHRS account. The connection appears publicly only after they accept.",color=Muted,style=MaterialTheme.typography.bodySmall);OutlinedTextField(email,{email=it.trimStart()},label={Text("Partner's registered email")},singleLine=true,modifier=Modifier.fillMaxWidth());ElectricButton(onClick={busy=true;vm.runTask{try{ApiClient.request("partners",HttpMethod.Post,obj("email" to email.trim()));email="";links=ApiClient.request("partners").jsonArray;vm.error.value="Partner invitation submitted."}finally{busy=false}}},enabled=email.contains('@')&&!busy){if(busy)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else{Icon(Icons.Default.PersonAdd,null);Text(" Send invitation")}};Text("Connections",color=Lavender,fontWeight=FontWeight.Bold);val current=links;if(current==null)Box(Modifier.fillMaxWidth().padding(30.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}else if(current.isEmpty())Text("No linked partners or invitations yet.",color=Muted,modifier=Modifier.padding(vertical=24.dp))else LazyColumn(Modifier.heightIn(max=390.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){items(current.size){index->val link=current[index].jsonObject;val partner=link.objectOrNull("partner");val status=link.str("status");val incoming=link.bool("incoming");Column(Modifier.fillMaxWidth().glassCard()){Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(50.dp).background(Electric.copy(alpha=.18f),CircleShape),contentAlignment=Alignment.Center){Text(partner?.str("displayName")?.firstOrNull()?.uppercaseChar()?.toString()?:"?",color=Lavender,fontWeight=FontWeight.Bold)};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(partner?.str("displayName")?:"Member",fontWeight=FontWeight.Bold,color=Color.White);Text(when{status=="ACCEPTED"->"Confirmed partner";status=="PENDING"&&incoming->"Invited you";status=="PENDING"->"Invitation pending";else->"Declined"},color=Muted,style=MaterialTheme.typography.bodySmall)};if(status=="ACCEPTED")IconButton({partner?.strOrNull("id")?.let(openProfile)}){Icon(Icons.Default.ChevronRight,"Open profile",tint=Lavender)}};if(status=="PENDING"&&incoming)Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){ElectricButton({respond(link,true)},Modifier.weight(1f)){Text("Accept")};OutlinedButton({respond(link,false)},Modifier.weight(1f)){Text("Decline")}}else TextButton({remove(link)},Modifier.align(Alignment.End)){Text(if(status=="ACCEPTED")"Unlink"else"Remove",color=Color(0xFFFFB4AB))}}}};OutlinedButton(dismiss,Modifier.fillMaxWidth()){Text("Done")};Spacer(Modifier.height(24.dp))}}}

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
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(dismiss,Modifier.weight(1f)){Text("Cancel")};ElectricButton(onClick={saving=true;vm.runTask{try{ApiClient.request("preferences",HttpMethod.Patch,buildJsonObject{put("showAge",showAge);put("showPosition",showPosition);put("hideOnline",hideOnline);put("hideDistance",hideDistance);put("pauseDiscovery",pauseDiscovery);put("browsePrivately",isPremium&&browsePrivately);put("markChattedOnGrid",markChatted);put("showGridMessageCount",showUnread);put("allowRequests",allowRequests);put("allowReadReceipts",readReceipts);put("notifications",notifications);put("notificationPreview",preview)});vm.refresh();Presence.reconnect();dismiss()}finally{saving=false}}},modifier=Modifier.weight(1f),enabled=!saving){if(saving)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else Text("Save")}}
        Spacer(Modifier.height(28.dp))
    }}
}

@Composable private fun SelectField(label:String,value:String,options:List<String>,display:(String)->String={if(it.isBlank())"Not specified"else it},select:(String)->Unit){var expanded by remember{mutableStateOf(false)};ExposedDropdownMenuBox(expanded,{expanded=it}){OutlinedTextField(value=display(value),onValueChange={},readOnly=true,label={Text(label)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},modifier=Modifier.fillMaxWidth().menuAnchor());ExposedDropdownMenu(expanded,{expanded=false}){options.forEach{option->DropdownMenuItem({Text(display(option))},{select(option);expanded=false})}}}}
@Composable private fun MultiChoiceField(label:String,options:List<String>,selected:Set<String>,maximum:Int,toggle:(String)->Unit){Column(Modifier.fillMaxWidth()){Text("$label · ${selected.size}/$maximum",color=Lavender,fontWeight=FontWeight.SemiBold);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){options.forEach{option->FilterChip(option in selected,{toggle(option)},label={Text(option)},enabled=option in selected||selected.size<maximum)}}}}
@Composable private fun EditorSection(title:String,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().glassCard(),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,color=Lavender,fontWeight=FontWeight.Bold);content()}}
@Composable private fun ProfileEditor(vm:AppViewModel,dismiss:()->Unit){
    val user=vm.user.value;val profile=user?.objectOrNull("profile");var photos by remember{mutableStateOf(user?.array("photos")?:JsonArray(emptyList()))}
    var photoBusy by remember{mutableStateOf(false)}
    var name by remember{mutableStateOf(profile?.str("displayName").orEmpty())};var bio by remember{mutableStateOf(profile?.str("bio").orEmpty())};var gender by remember{mutableStateOf(profile?.strOrNull("gender")?:"Not specified")};var identity by remember{mutableStateOf(profile?.strOrNull("identity").orEmpty())};var pronouns by remember{mutableStateOf(profile?.strOrNull("pronouns")?:"Prefer not to say")};var position by remember{mutableStateOf(profile?.strOrNull("position")?:"Prefer not to say")};var cut by remember{mutableStateOf(profile?.strOrNull("circumcisionStatus")?:"Do not show")};var bodyType by remember{mutableStateOf(profile?.strOrNull("bodyType")?:"Do not show")};var relationship by remember{mutableStateOf(profile?.strOrNull("relationshipStatus")?:"Do not show")};var catchMeAt by remember{mutableStateOf(profile?.strOrNull("catchMeAt").orEmpty())};var explicitPics by remember{mutableStateOf(profile?.strOrNull("acceptExplicitPics")?:"Never")}
    var race by remember{mutableStateOf(profile?.strOrNull("race").orEmpty())};var bodyHair by remember{mutableStateOf(profile?.strOrNull("bodyHair").orEmpty())};var religion by remember{mutableStateOf(profile?.strOrNull("religion").orEmpty())};var interests by remember{mutableStateOf(profile?.array("interests")?.joinToString(", "){it.jsonPrimitive.content}.orEmpty())};var tags by remember{mutableStateOf(profile?.array("tags")?.joinToString(", "){it.jsonPrimitive.content}.orEmpty())}
    var intentions by remember{mutableStateOf(profile?.array("intentions")?.mapNotNull{it.jsonPrimitive.contentOrNull}?.toSet()?:emptySet())};var tribes by remember{mutableStateOf(profile?.strOrNull("clanTribe")?.split(',')?.map{it.trim()}?.filter{it.isNotBlank()}?.toSet()?:emptySet())};var kinks by remember{mutableStateOf(profile?.array("kinks")?.mapNotNull{it.jsonPrimitive.contentOrNull}?.toSet()?:emptySet())}
    var height by remember{mutableStateOf(profile?.get("heightCm")?.jsonPrimitive?.intOrNull?.toString().orEmpty())};var weight by remember{mutableStateOf(profile?.get("weightKg")?.jsonPrimitive?.intOrNull?.toString().orEmpty())};var hivStatus by remember{mutableStateOf(profile?.strOrNull("hivStatus")?:"Do not show")};var hivDate by remember{mutableStateOf(profile?.strOrNull("hivLastTestDate")?.take(10).orEmpty())};var prep by remember{mutableStateOf(profile?.strOrNull("prepStatus").orEmpty())};var visible by remember{mutableStateOf(profile?.get("visible")?.jsonPrimitive?.booleanOrNull?:true)}
    val social=profile?.objectOrNull("socialLinks");var instagram by remember{mutableStateOf(social?.strOrNull("instagram").orEmpty())};var facebook by remember{mutableStateOf(social?.strOrNull("facebook").orEmpty())};var tiktok by remember{mutableStateOf(social?.strOrNull("tiktok").orEmpty())};var xLink by remember{mutableStateOf(social?.strOrNull("x").orEmpty())};var snapchat by remember{mutableStateOf(social?.strOrNull("snapchat").orEmpty())};var website by remember{mutableStateOf(social?.strOrNull("website").orEmpty())};var saving by remember{mutableStateOf(false)}
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{vm.runTask{uploadFileFromUri(context,it,"profiles/photos","photo");vm.refresh();photos=vm.user.value?.array("photos")?:JsonArray(emptyList())}}}
    fun makePrimary(photo:JsonObject){photoBusy=true;vm.runTask{try{ApiClient.request("profiles/photos/${photo.str("id")}/primary",HttpMethod.Patch);vm.refresh();photos=vm.user.value?.array("photos")?:JsonArray(emptyList())}finally{photoBusy=false}}}
    fun movePhoto(from:Int,to:Int){if(to !in photos.indices||from==to)return;val next=photos.toMutableList().apply{add(to,removeAt(from))};photoBusy=true;vm.runTask{try{ApiClient.request("profiles/photos/reorder",HttpMethod.Patch,buildJsonObject{put("photoIds",buildJsonArray{next.forEach{add(it.jsonObject.str("id"))}})});vm.refresh();photos=vm.user.value?.array("photos")?:JsonArray(emptyList())}finally{photoBusy=false}}}
    val genderOptions=listOf("Male","Cis Male","Trans Male","Non-Binary","Man","Woman","Non-binary","Not specified");val intentionOptions=listOf("Friends","Networking","Fun","Relationship","The Love of My Life","DL");val pronounOptions=listOf("He/Him/His","She/Her/Hers","They/Them/Theirs","Ze/Hir/Hirs","Ze/Zir/Zirs","Use my name only","Ask me","Prefer not to say");val tribeOptions=listOf("Bear","Clean Cut","Daddy","Discreet","Geek","Interracial","Jock","Leather","Otter","Poz","Rugged","Trans","Twink","Sober");val kinkOptions=listOf("Group","Couples","One on One","Oral Only","Threesomes","Public","BDSM","Slave/Master","Toy Fun")
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=Panel){Column(Modifier.fillMaxWidth().heightIn(max=790.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("Edit profile",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White)
        EditorSection("Profile photos"){
            val primaryId=photos.map{it.jsonObject}.firstOrNull{it.str("moderationStatus")=="APPROVED"}?.str("id")
            if(photos.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){photos.forEachIndexed{index,item->val photo=item.jsonObject;val approved=photo.str("moderationStatus")=="APPROVED";val primary=photo.str("id")==primaryId;Column(Modifier.width(132.dp).border(if(primary)2.dp else 1.dp,if(primary)Electric else Color.White.copy(alpha=.14f),RoundedCornerShape(16.dp)).padding(5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){Box{AsyncImage(authenticatedImageModel(photo.strOrNull("url")),null,Modifier.fillMaxWidth().height(142.dp).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Crop);IconButton({photoBusy=true;vm.runTask{try{ApiClient.request("profiles/photos/${photo.str("id")}",HttpMethod.Delete);vm.refresh();photos=vm.user.value?.array("photos")?:JsonArray(emptyList())}finally{photoBusy=false}}},Modifier.align(Alignment.TopEnd).size(30.dp).background(Color.Black.copy(alpha=.7f),CircleShape),enabled=!photoBusy){Icon(Icons.Default.Close,"Remove photo",tint=Color.White,modifier=Modifier.size(17.dp))};if(primary)Text("PRIMARY",color=Color.White,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.BottomStart).padding(6.dp).background(Electric,RoundedCornerShape(50)).padding(horizontal=7.dp,vertical=3.dp))};Text(if(approved)if(primary)"Default photo"else"Approved"else photo.str("moderationStatus").lowercase().replaceFirstChar{it.uppercase()},color=if(primary)Lavender else Muted,style=MaterialTheme.typography.labelSmall);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){IconButton({movePhoto(index,index-1)},enabled=!photoBusy&&index>0){Icon(Icons.Default.ChevronLeft,"Move left")};IconButton({movePhoto(index,index+1)},enabled=!photoBusy&&index<photos.lastIndex){Icon(Icons.Default.ChevronRight,"Move right")}};OutlinedButton({makePrimary(photo)},Modifier.fillMaxWidth(),enabled=!photoBusy&&approved&&!primary,contentPadding=PaddingValues(horizontal=5.dp,vertical=2.dp)){Icon(Icons.Default.Star,null,Modifier.size(16.dp));Text(if(primary)" Primary"else" Make primary",style=MaterialTheme.typography.labelSmall)}}}};Text("Use the arrows to arrange your photos. Your primary approved photo appears first on your profile.",color=Muted,style=MaterialTheme.typography.bodySmall);OutlinedButton({picker.launch("image/*")},Modifier.fillMaxWidth(),enabled=!photoBusy){Icon(Icons.Default.AddPhotoAlternate,null);Text(" Add profile photo")}}
        EditorSection("Your profile"){OutlinedTextField(name,{name=it.take(40)},label={Text("Display name")},modifier=Modifier.fillMaxWidth());SelectField("Gender",gender,genderOptions,select={gender=it});MultiChoiceField("Looking for",intentionOptions,intentions,3){intentions=if(it in intentions)intentions-it else intentions+it};OutlinedTextField(bio,{bio=it.take(500)},label={Text("Bio")},minLines=4,modifier=Modifier.fillMaxWidth());PrivacyToggle("Visible in discovery",visible){visible=it}}
        EditorSection("Identity"){SelectField("Identity",identity,listOf("","Gay","Bisexual","Straight"),select={identity=it});SelectField("Pronouns",pronouns,pronounOptions,select={pronouns=it});SelectField("Position",position,listOf("Prefer not to say","Top","Top / Vers","Vers","Bottom / Vers","Bottom","Side"),select={position=it});SelectField("Cut / Uncut",cut,listOf("Do not show","Cut","Uncut"),select={cut=it});SelectField("Body type",bodyType,listOf("Do not show","Toned","Average","Large","Muscular","Slim","Stocky"),select={bodyType=it});SelectField("Relationship",relationship,listOf("Do not show","Committed","Dating","Engaged","Exclusive","Married","Open Relationship","Partnered","Single"),select={relationship=it});SelectField("Catch me at",catchMeAt,listOf("","My place","Your place","Coffee shop","Public area","Other"),select={catchMeAt=it});SelectField("Explicit pictures",explicitPics,listOf("Never","Not at first","Yes Please"),select={explicitPics=it})}
        EditorSection("About you"){OutlinedTextField(race,{race=it.take(60)},label={Text("Race")},modifier=Modifier.fillMaxWidth());OutlinedTextField(bodyHair,{bodyHair=it.take(60)},label={Text("Body hair")},modifier=Modifier.fillMaxWidth());OutlinedTextField(religion,{religion=it.take(80)},label={Text("Religion")},modifier=Modifier.fillMaxWidth());MultiChoiceField("Tribes",tribeOptions,tribes,3){tribes=if(it in tribes)tribes-it else tribes+it};OutlinedTextField(interests,{interests=it},label={Text("Interests, comma separated · max 12")},modifier=Modifier.fillMaxWidth());OutlinedTextField(tags,{tags=it},label={Text("Tags, comma separated · max 10")},modifier=Modifier.fillMaxWidth());MultiChoiceField("Kinks",kinkOptions,kinks,3){kinks=if(it in kinks)kinks-it else kinks+it}}
        EditorSection("Physical & sexual health"){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(height,{height=it.filter(Char::isDigit).take(3)},label={Text("Height cm")},modifier=Modifier.weight(1f));OutlinedTextField(weight,{weight=it.filter(Char::isDigit).take(3)},label={Text("Weight kg")},modifier=Modifier.weight(1f))};SelectField("HIV status",hivStatus,listOf("Do not show","Negative","Positive","Positive, Undetectable","Lets chat about it"),select={hivStatus=it});OutlinedTextField(hivDate,{hivDate=it.take(10)},label={Text("Last test date · YYYY-MM-DD")},modifier=Modifier.fillMaxWidth());SelectField("PrEP status",prep,listOf("","On PrEP","Not on PrEP"),select={prep=it})}
        EditorSection("Social links"){listOf("Instagram" to instagram,"Facebook" to facebook,"TikTok" to tiktok,"X" to xLink,"Snapchat" to snapchat,"Website" to website).forEach{(label,value)->OutlinedTextField(value,{next->when(label){"Instagram"->instagram=next;"Facebook"->facebook=next;"TikTok"->tiktok=next;"X"->xLink=next;"Snapchat"->snapchat=next;else->website=next}},label={Text("$label handle or URL")},modifier=Modifier.fillMaxWidth())}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(dismiss,Modifier.weight(1f)){Text("Cancel")};ElectricButton({val parsedInterests=interests.split(',').map{it.trim()}.filter{it.isNotBlank()}.distinct().take(12);val parsedTags=tags.split(',').map{it.trim().removePrefix("#")}.filter{it.isNotBlank()}.distinctBy{it.lowercase()}.take(10);saving=true;vm.runTask{try{ApiClient.request("profiles/me",HttpMethod.Patch,buildJsonObject{put("displayName",name.trim());put("bio",bio.trim());put("gender",gender);put("identity",identity);put("pronouns",pronouns);put("position",position);put("circumcisionStatus",cut);put("bodyType",bodyType);put("relationshipStatus",relationship);put("catchMeAt",catchMeAt);put("acceptExplicitPics",explicitPics);put("race",race.trim());put("bodyHair",bodyHair.trim());put("religion",religion.trim());put("clanTribe",tribes.sorted().joinToString(","));put("interests",buildJsonArray{parsedInterests.forEach{add(it)}});put("tags",buildJsonArray{parsedTags.forEach{add(it)}});put("intentions",buildJsonArray{intentions.forEach{add(it)}});put("kinks",buildJsonArray{kinks.forEach{add(it)}});put("heightCm",height.toIntOrNull()?.let{JsonPrimitive(it)}?:JsonNull);put("weightKg",weight.toIntOrNull()?.let{JsonPrimitive(it)}?:JsonNull);put("hivStatus",hivStatus);put("hivLastTestDate",hivDate.takeIf{it.isNotBlank()}?.let{JsonPrimitive(it)}?:JsonNull);put("prepStatus",prep.takeIf{it.isNotBlank()}?.let{JsonPrimitive(it)}?:JsonNull);put("visible",visible);put("socialLinks",buildJsonObject{put("instagram",instagram.trim());put("facebook",facebook.trim());put("tiktok",tiktok.trim());put("x",xLink.trim());put("snapchat",snapchat.trim());put("website",website.trim())})});vm.refresh();dismiss()}finally{saving=false}}},Modifier.weight(1f),enabled=!saving&&name.trim().length>=2){if(saving)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else Text("Save")}}
        Spacer(Modifier.height(28.dp))
    }}
}

@Composable private fun SharedGalleries(vm:AppViewModel,dismiss:()->Unit){
    val context=LocalContext.current
    var owned by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))}
    var received by remember{mutableStateOf<JsonArray>(JsonArray(emptyList()))}
    var selected by remember{mutableStateOf<String?>(null)}
    var opened by remember{mutableStateOf<JsonObject?>(null)}
    var newTitle by remember{mutableStateOf("")}
    var confirmGalleryDelete by remember{mutableStateOf(false)}
    var confirmPhotoDelete by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    suspend fun reload(){
        val result=ApiClient.request("private-galleries")
        owned=if(result is JsonArray)result else result.jsonObject.array("items")
        received=ApiClient.request("private-galleries/sharing").jsonObject.array("sharedWithMe")
    }
    LaunchedEffect(Unit){vm.runTask{reload()}}
    val gallery=owned.firstOrNull{it.jsonObject.str("id")==selected}?.jsonObject
    val upload=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
        if(uri!=null && selected!=null)vm.runTask{
            busy=true
            try{uploadFileFromUri(context,uri,"private-galleries/$selected/photos","photo");reload()}
            finally{busy=false}
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest=dismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(12.dp).background(Panel,RoundedCornerShape(20.dp)).padding(16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                IconButton({if(selected!=null)selected=null else dismiss()}){Icon(Icons.Default.ArrowBack,"Back")}
                Text(gallery?.str("title")?:"Private galleries",Modifier.weight(1f),color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge)
                IconButton(dismiss){Icon(Icons.Default.Close,"Close")}
            }
            if(gallery==null){
                Row(verticalAlignment=Alignment.CenterVertically){
                    OutlinedTextField(newTitle,{newTitle=it.take(60)},Modifier.weight(1f),label={Text("Gallery name")},singleLine=true)
                    IconButton({vm.runTask{busy=true;try{
                        val created=ApiClient.request("private-galleries",HttpMethod.Post,obj("title" to newTitle.trim())).jsonObject
                        newTitle="";reload();selected=created.str("id")
                    }finally{busy=false}}},enabled=newTitle.isNotBlank()&&!busy){Icon(Icons.Default.AddCircle,"Create gallery",tint=Lavender)}
                }
                Text("Your galleries",color=Lavender,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
                LazyColumn(Modifier.weight(1f)){
                    items(owned.size){index->
                        val g=owned[index].jsonObject
                        ListItem(headlineContent={Text(g.str("title"))},supportingContent={Text("${g.array("photos").size} photos")},trailingContent={Icon(Icons.Default.ChevronRight,null)},modifier=Modifier.clickable{selected=g.str("id")})
                    }
                    item{Text("Shared with you",color=Lavender,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=16.dp))}
                    items(received.size){index->
                        val g=received[index].jsonObject
                        ListItem(headlineContent={Text(g.str("title"))},supportingContent={Text("From ${g.objectOrNull("owner")?.str("name").orEmpty()}")},modifier=Modifier.clickable{vm.runTask{opened=ApiClient.request("private-galleries/${g.str("id")}/view").jsonObject}})
                    }
                }
            }else{
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={upload.launch("image/*")},enabled=!busy){Icon(Icons.Default.AddPhotoAlternate,null);Text(" Add photos")}
                    OutlinedButton(onClick={confirmGalleryDelete=true},enabled=!busy){Icon(Icons.Default.Delete,null);Text(" Delete gallery")}
                }
                val photos=gallery.array("photos")
                Text("${photos.size} photos · Use arrows to reorder",color=Muted)
                LazyColumn(Modifier.weight(1f)){
                    items(photos.size){index->
                        val photo=photos[index].jsonObject
                        Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                            AsyncImage(authenticatedImageModel(photo.str("url")),null,Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Crop)
                            Spacer(Modifier.weight(1f))
                            IconButton({vm.runTask{busy=true;try{
                                val ids=photos.map{it.jsonObject.str("id")}.toMutableList()
                                java.util.Collections.swap(ids,index,index-1)
                                ApiClient.request("private-galleries/$selected/reorder",HttpMethod.Post,obj("photoIds" to ids));reload()
                            }finally{busy=false}}},enabled=index>0&&!busy){Icon(Icons.Default.KeyboardArrowUp,"Move up")}
                            IconButton({vm.runTask{busy=true;try{
                                val ids=photos.map{it.jsonObject.str("id")}.toMutableList()
                                java.util.Collections.swap(ids,index,index+1)
                                ApiClient.request("private-galleries/$selected/reorder",HttpMethod.Post,obj("photoIds" to ids));reload()
                            }finally{busy=false}}},enabled=index<photos.lastIndex&&!busy){Icon(Icons.Default.KeyboardArrowDown,"Move down")}
                            IconButton({confirmPhotoDelete=photo.str("id")},enabled=!busy){Icon(Icons.Default.Delete,"Remove photo",tint=Color.Red)}
                        }
                    }
                }
            }
        }
    }
    if(confirmGalleryDelete)AlertDialog(onDismissRequest={confirmGalleryDelete=false},title={Text("Delete gallery?")},text={Text("The gallery and its photos will be deleted and access revoked.")},confirmButton={TextButton({confirmGalleryDelete=false;vm.runTask{busy=true;try{ApiClient.request("private-galleries/$selected",HttpMethod.Delete);selected=null;reload()}finally{busy=false}}}){Text("Delete",color=Color.Red)}},dismissButton={TextButton({confirmGalleryDelete=false}){Text("Cancel")}})
    confirmPhotoDelete?.let{photoId->
        AlertDialog(onDismissRequest={confirmPhotoDelete=null},title={Text("Remove photo?")},text={Text("Remove this photo from the gallery?")},confirmButton={TextButton({confirmPhotoDelete=null;vm.runTask{busy=true;try{ApiClient.request("private-galleries/$selected/photos/$photoId",HttpMethod.Delete);reload()}finally{busy=false}}}){Text("Remove",color=Color.Red)}},dismissButton={TextButton({confirmPhotoDelete=null}){Text("Cancel")}})
    }
    opened?.let{SharedGalleryViewer(it,vm){opened=null}}
}
