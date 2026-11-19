package za.co.aftrhrs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlinx.coroutines.withTimeoutOrNull
import za.co.aftrhrs.AppViewModel
import za.co.aftrhrs.data.*
import java.time.Instant

private val PulseAccent = Color(0xFFD95BFF)
private val PulseSurface = Color(0xFF211927)
private val PulseMuted = Color(0xFFB8AEBE)

private class PulseState {
    val repo = PulseRepository()
    var me by mutableStateOf<PulseMeResponse?>(null); var pulses by mutableStateOf<List<PulseSummary>>(emptyList())
    var loading by mutableStateOf(false); var loadingMore by mutableStateOf(false); var error by mutableStateOf<String?>(null)
    var cursor by mutableStateOf<String?>(null); var ended by mutableStateOf(false); var now by mutableLongStateOf(System.currentTimeMillis())
    var offset by mutableLongStateOf(0L); var detail by mutableStateOf<PulseSummary?>(null); var detailLoading by mutableStateOf(false)
    var busy by mutableStateOf(false); var requestGeneration by mutableIntStateOf(0); var retryAt by mutableLongStateOf(0)
    val pagination = PulsePagination(); var pageJob: Job? = null; var refreshJob: Job? = null
    val active: PulseSession? get() = me?.session?.takeIf { pulseRemainingSeconds(it.expiresAt, now, offset) > 0 }

    suspend fun refreshMe() {
        try { val value=repo.me(); me=value; updateOffset(value.serverTime); if(value.session!=null && active==null) { me=value.copy(session=null); repo.end() } }
        catch(e:Exception) { error=prettyPulseError(e) }
    }
    private fun updateOffset(raw:String?) { raw?.let { runCatching { offset=Instant.parse(it).toEpochMilli()-System.currentTimeMillis() } } }
    suspend fun refresh() { requestGeneration++; val generation=requestGeneration; pageJob?.cancel();refreshJob?.cancel();pagination.reset();cursor=null;ended=false;loading=true;loadingMore=false;error=null;pulses=emptyList(); try { fetch(null,generation,true) } finally { if(generation==requestGeneration)loading=false } }
    suspend fun more() { if(loading||loadingMore||ended)return; val c=cursor ?: return; if(!pagination.begin(c)){ended=true;cursor=null;return}; fetch(c,requestGeneration,false) }
    private suspend fun fetch(c:String?,generation:Int,replace:Boolean) {
        if(generation!=requestGeneration)return
        if(c==null)pagination.begin(null)
        if(loadingMore)return
        loadingMore=!replace
        val job=kotlinx.coroutines.currentCoroutineContext()[Job]
        try {
            val page=repo.discover(c); if(generation!=requestGeneration || job?.isActive==false)return
            updateOffset(page.serverTime)
            val fresh=page.items.filter { pulseRemainingSeconds(it.expiresAt,System.currentTimeMillis(),offset)>0 }
            pulses=if(replace)fresh else { val ids=pulses.mapTo(mutableSetOf()){it.id}; pulses+fresh.filter{ids.add(it.id)} }
            cursor=page.nextCursor; ended=page.nextCursor==null
            val canContinue=pagination.continueAfter(fresh.size,page.nextCursor,c)
            if(!canContinue&&page.nextCursor!=null){cursor=null;ended=true}
            if(fresh.isEmpty()&&cursor!=null&&canContinue) more()
        } catch(e:Exception) { if(generation==requestGeneration) error=prettyPulseError(e) }
        finally { if(generation==requestGeneration)loadingMore=false }
    }
    suspend fun open(summary:PulseSummary) { detail=summary; detailLoading=true; try { val result=repo.detail(summary.id); updateOffset(result.serverTime); if(result.pulse==null) { unavailable(summary.id); detail=null } else detail=result.pulse } catch(e:Exception) { error=prettyPulseError(e); if(prettyPulseCode(e)=="PULSE_UNAVAILABLE"){unavailable(summary.id);detail=null} } finally { detailLoading=false } }
    fun unavailable(id:String){pulses=pulses.filterNot{it.id==id}; if(pulses.isEmpty()&&cursor!=null)refreshJob=null}
}

private fun prettyPulseCode(e:Throwable):String? = (e as? ApiException)?.code ?: Regex("[A-Z_]{4,}").find(e.message.orEmpty())?.value
private fun prettyPulseError(e:Throwable):String = when(prettyPulseCode(e)) {
    "UNAUTHORIZED" -> "Your AFTRHRS session has expired. Sign in again to continue."
    "PULSE_INELIGIBLE" -> "Your account is not currently eligible to publish a Pulse. Preferences can still be saved."
    "PREMIUM_REQUIRED" -> "Pulse publishing requires Premium or an active promotion."
    "PULSE_UNAVAILABLE" -> "This Pulse is no longer available."
    "RATE_LIMITED", "RETRY_REQUEST" -> "Please wait ${(e as? ApiException)?.retryAfterSeconds?.coerceAtLeast(1) ?: 2} seconds before retrying this Pulse request."
    "IDEMPOTENCY_CONFLICT" -> "This response changed during the previous attempt. Edit it to try again."
    "INVALID_JSON", "INVALID_PULSE", "INVALID_RESPONSE", "INVALID_LIMIT", "INVALID_CURSOR", "INVALID_PULSE_ID" -> "Pulse details could not be processed. Refresh and try again."
    "PULSE_RESPONSE_FAILED" -> "Your response could not be sent. You can safely retry."
    else -> "Pulse could not be loaded. Check your connection and try again."
}

@Composable fun PulseScreen(vm:AppViewModel,openPremium:()->Unit,close:()->Unit) {
    val state=remember { PulseState() }; val scope= rememberCoroutineScope(); val lifecycle= LocalLifecycleOwner.current
    var composer by rememberSaveable { mutableStateOf(false) }; var selected by remember { mutableStateOf<PulseSummary?>(null) }; var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit){ state.refreshMe();state.refresh() }
    LaunchedEffect(lifecycle){ lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){ while(true){ clock=System.currentTimeMillis();state.now=clock;state.pulses=state.pulses.filter{pulseRemainingSeconds(it.expiresAt,clock,state.offset)>0};delay(60_000);if(state.active==null&&state.me?.session!=null)state.refreshMe() } } }
    BackHandler { when { selected!=null->{selected=null;state.detail=null};composer->composer=false;else->close() } }
    if(composer){ PulseComposer(vm,state,onClose={composer=false},openPremium=openPremium);return }
    selected?.let { pulse-> PulseDetail(vm,state,pulse,onClose={selected=null;state.detail=null});return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){IconButton(close){Icon(Icons.Default.ArrowBack,"Back")};Column(Modifier.weight(1f)){Text("Pulse",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White);Text("Discover what members are feeling right now",color=PulseMuted)};Icon(Icons.Default.GraphicEq,"Pulse",tint=PulseAccent,modifier=Modifier.size(30.dp))}
        if(state.error!=null && state.pulses.isEmpty()) Column(Modifier.fillMaxWidth().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Text(state.error!!,color=PulseMuted,textAlign=TextAlign.Center);ElectricButton({scope.launch{state.refreshMe();state.refresh()}}){Text("Try again")}}
        else PullToRefreshBox(isRefreshing=state.loading,onRefresh={scope.launch{state.refreshMe();state.refresh() }},modifier=Modifier.fillMaxSize()) {
            LazyColumn(contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Column(Modifier.fillMaxWidth().background(PulseSurface,RoundedCornerShape(22.dp)).border(1.dp,PulseAccent.copy(alpha=.35f),RoundedCornerShape(22.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) { Text("THE MOMENT IS YOURS",color=PulseAccent,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium);Text("A little intention can start something real.",color=Color.White,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Text("Share what you’re open to and discover other active Pulses.",color=PulseMuted);ElectricButton({composer=true},Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Text(if(state.active!=null)" Manage my Pulse" else " Create my Pulse")} } }
                item { state.active?.let { ActivePulseCard(it,state,clock, onEdit={composer=true},onEnd={scope.launch{state.busy=true;try{state.repo.end();state.refreshMe();state.refresh()}catch(e:Exception){state.error=prettyPulseError(e)}finally{state.busy=false}}}) } ?: EligibilityCard(state,openPremium) }
                item { Row(verticalAlignment=Alignment.CenterVertically){Text("Active Pulses",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.weight(1f));Text("Not limited by Grid radius",style=MaterialTheme.typography.labelSmall,color=PulseMuted)} }
                if(state.pulses.isEmpty()&&!state.loading&&state.error==null)item{Column(Modifier.fillMaxWidth().padding(vertical=34.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){Icon(Icons.Default.GraphicEq,null,tint=PulseAccent,modifier=Modifier.size(46.dp));Text("No active Pulses yet",fontWeight=FontWeight.Bold,color=Color.White);Text("Check back soon, or share your own intention.",color=PulseMuted,textAlign=TextAlign.Center)}}
                items(state.pulses,key={it.id}){pulse->PulseCard(pulse,clock,state.offset,onClick={selected=pulse;scope.launch{state.open(pulse)}})}
                if(state.error!=null&&state.pulses.isNotEmpty())item{Row(verticalAlignment=Alignment.CenterVertically){Text("More Pulses could not load. Your current list is saved.",color=PulseMuted,modifier=Modifier.weight(1f));TextButton({scope.launch{state.error=null;state.more()}}){Text("Retry")}}}
                if(!state.ended)item { LaunchedEffect(state.cursor,state.pulses.size,state.loadingMore){ if(!state.loadingMore)state.more() }; if(state.loadingMore)Box(Modifier.fillMaxWidth().padding(12.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(color=PulseAccent)} else Spacer(Modifier.height(1.dp)) }
            }
        }
    }
}

@Composable private fun EligibilityCard(state:PulseState,openPremium:()->Unit){val entitlement=state.me?.entitlement;Column(Modifier.fillMaxWidth().background(PulseSurface,RoundedCornerShape(18.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(if(entitlement?.canPublish==true)if(entitlement.source=="HAPPY_HOUR")"Happy Hour access is active"else"Publishing access is active"else"Your Pulse preferences are ready",fontWeight=FontWeight.Bold,color=Color.White);Text(when{state.me?.eligible==false->"Your account is not currently eligible to publish.";entitlement?.canPublish==false->"Publishing normally requires Premium. Access is confirmed by AFTRHRS.";else->"Save your settings privately or publish when you’re ready."},color=PulseMuted);if(state.me?.eligible!=false&&entitlement?.canPublish==false)TextButton(openPremium){Text("Explore Premium",color=PulseAccent)}}}
@Composable private fun ActivePulseCard(pulse:PulseSession,state:PulseState,now:Long,onEdit:()->Unit,onEnd:()->Unit){Column(Modifier.fillMaxWidth().background(PulseSurface,RoundedCornerShape(18.dp)).padding(15.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("YOUR PULSE IS LIVE",color=PulseAccent,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text(remainingLabel(pulse.expiresAt,now,state.offset),color=PulseAccent)};Text(PulseIntention.entries.firstOrNull{it==pulse.primaryIntent}?.title.orEmpty(),color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);if(pulse.secondaryIntents.isNotEmpty())Text(pulse.secondaryIntents.joinToString(" · "){it.title},color=PulseMuted);pulse.message?.takeIf{it.isNotBlank()}?.let{Text(it,color=Color.White)};Text("${pulse.visibility.title} · visible to ${pulse.visibility.title.lowercase()}",color=PulseMuted);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onEdit){Text("Edit Pulse")};OutlinedButton(onEnd,enabled=!state.busy){Text("End Pulse")}}} }
private fun remainingLabel(expiry:String,now:Long,offset:Long):String{val seconds=pulseRemainingSeconds(expiry,now,offset);return when{seconds<=0->"Expired";seconds<=60->"<1 min left";else->"${(seconds+59)/60}m left"}}
@Composable private fun PulseCard(p:PulseSummary,now:Long,offset:Long,onClick:()->Unit){Column(Modifier.fillMaxWidth().background(PulseSurface,RoundedCornerShape(18.dp)).border(1.dp,PulseAccent.copy(alpha=.24f),RoundedCornerShape(18.dp)).clickable(onClick=onClick).padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(verticalAlignment=Alignment.Top){Column(Modifier.weight(1f)){Text(p.displayName?:"Member",color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium);Text(p.primaryIntent.title,color=PulseAccent,fontWeight=FontWeight.SemiBold)};Text(remainingLabel(p.expiresAt,now,offset),color=PulseAccent,style=MaterialTheme.typography.labelSmall)};if(p.secondaryIntents.isNotEmpty())Text(p.secondaryIntents.joinToString(" · "){it.title},color=PulseMuted);p.message?.takeIf{it.isNotBlank()}?.let{Text(it,color=Color.White,maxLines=3)};Text("View and respond  ›",color=PulseAccent,fontWeight=FontWeight.SemiBold,modifier=Modifier.align(Alignment.End))}}

private data class ComposerValues(val primary:PulseIntention=PulseIntention.RIGHT_NOW,val secondary:List<PulseIntention> = emptyList(),val message:String="",val visibility:PulseVisibility=PulseVisibility.GRID,val duration:Int=60)
private val composerValuesSaver = listSaver<MutableState<ComposerValues>,String>(
    save={state->listOf(state.value.primary.wire,state.value.secondary.joinToString(","){it.wire},state.value.message,state.value.visibility.wire,state.value.duration.toString())},
    restore={saved->mutableStateOf(ComposerValues(
        primary=PulseIntention.from(saved.getOrNull(0))?:PulseIntention.RIGHT_NOW,
        secondary=saved.getOrNull(1)?.split(",")?.mapNotNull(PulseIntention::from)?.take(3).orEmpty(),
        message=saved.getOrNull(2).orEmpty(),
        visibility=PulseVisibility.from(saved.getOrNull(3))?:PulseVisibility.GRID,
        duration=saved.getOrNull(4)?.toIntOrNull()?.takeIf{it in setOf(30,60,120,240)}?:60
    ))}
)
@Composable private fun PulseComposer(vm:AppViewModel,state:PulseState,onClose:()->Unit,openPremium:()->Unit){var form by rememberSaveable(saver=composerValuesSaver){mutableStateOf(ComposerValues())};var saving by remember{mutableStateOf(false)};var initialized by remember{mutableStateOf(false)};val scope=rememberCoroutineScope();val active=state.active
    LaunchedEffect(active,state.me?.preferences){if(!initialized){val p=active;val prefs=state.me?.preferences;form=if(p!=null)ComposerValues(p.primaryIntent,p.secondaryIntents.filter{it!=p.primaryIntent}.take(3),p.message.orEmpty(),p.visibility,60)else if(prefs!=null)ComposerValues(prefs.primaryIntent?:PulseIntention.RIGHT_NOW,prefs.secondaryIntents.filter{it!=prefs.primaryIntent}.take(3),prefs.message.orEmpty(),prefs.visibility?:PulseVisibility.GRID,prefs.durationMinutes?:60)else form;initialized=true}}
    BackHandler(enabled=saving){ };Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClose,enabled=!saving){Icon(Icons.Default.Close,"Close")};Text(if(active!=null)"Edit Pulse"else"New Pulse",style=MaterialTheme.typography.headlineSmall,color=Color.White,fontWeight=FontWeight.Bold)};PulseSection("Primary intention · choose one"){PulseIntention.entries.forEach{option->ChoiceRow(option.title,form.primary==option){form=form.copy(primary=option,secondary=form.secondary.filter{it!=option})}}};PulseSection("Also open to · up to three"){PulseIntention.entries.filter{it!=form.primary}.forEach{option->ChoiceRow(option.title,option in form.secondary,enabled=option in form.secondary||form.secondary.size<3){form=form.copy(secondary=if(option in form.secondary)form.secondary-option else form.secondary+option)}}};PulseSection("Optional message"){OutlinedTextField(form.message,{form=form.copy(message=it.take(120))},modifier=Modifier.fillMaxWidth(),label={Text("Add a short note")},keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences),maxLines=4);Text("${form.message.length}/120",Modifier.align(Alignment.End),color=PulseMuted)};PulseSection("Who can see this?"){PulseVisibility.entries.forEach{option->Column(Modifier.fillMaxWidth().clickable{form=form.copy(visibility=option)}.padding(vertical=7.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(option.title,color=Color.White,modifier=Modifier.weight(1f));RadioButton(form.visibility==option,{form=form.copy(visibility=option)})};Text(option.description,color=PulseMuted,style=MaterialTheme.typography.bodySmall)}}};PulseSection("Duration"){Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf(30,60,120,240).forEach{n->FilterChip(form.duration==n,{form=form.copy(duration=n)},label={Text("$n min")})}}};if(state.me?.eligible==false)Text("Your account is not currently eligible. Premium may not change account eligibility.",color=PulseMuted);Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){OutlinedButton(onClick={if(!saving){saving=true;scope.launch{try{state.repo.save(payload(form));state.refreshMe();onClose()}catch(e:Exception){state.error=prettyPulseError(e)}finally{saving=false}}}},modifier=Modifier.weight(1f),enabled=!saving){Text("Save preferences")};ElectricButton(onClick={if(!saving){saving=true;scope.launch{try{state.repo.publish(payload(form));state.refreshMe();state.refresh();onClose()}catch(e:Exception){val code=prettyPulseCode(e);state.error=prettyPulseError(e);if(code=="PREMIUM_REQUIRED"&&state.me?.eligible!=false)openPremium()}finally{saving=false}}}},modifier=Modifier.weight(1f),enabled=!saving&&state.me?.eligible!=false){if(saving)CircularProgressIndicator(Modifier.size(18.dp),color=Color.White,strokeWidth=2.dp)else Text(if(active!=null)"Update Pulse"else"Publish Pulse")}};Spacer(Modifier.height(30.dp))}}
private fun payload(f:ComposerValues)=PulseSavePayload(f.primary,f.secondary.distinct().filter{it!=f.primary}.take(3),f.message.trim().ifBlank{null},f.visibility,f.duration)
@Composable private fun PulseSection(title:String,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().background(PulseSurface,RoundedCornerShape(18.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text(title,color=PulseAccent,fontWeight=FontWeight.Bold);content()}}
@Composable private fun ChoiceRow(title:String,checked:Boolean,enabled:Boolean=true,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onClick).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Text(title,color=if(enabled)Color.White else PulseMuted,modifier=Modifier.weight(1f));Checkbox(checked,{onClick()},enabled=enabled,colors=CheckboxDefaults.colors(checkedColor=PulseAccent))}}

@Composable private fun PulseDetail(vm:AppViewModel,state:PulseState,summary:PulseSummary,onClose:()->Unit){var text by rememberSaveable(summary.id){mutableStateOf("")};var sending by remember{mutableStateOf(false)};var attempt by rememberSaveable(summary.id){mutableStateOf<PulseResponseSubmission?>(null)};val scope=rememberCoroutineScope();Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClose,enabled=!sending){Icon(Icons.Default.ArrowBack,"Back")};Text("Pulse details",style=MaterialTheme.typography.headlineSmall,color=Color.White,fontWeight=FontWeight.Bold)};if(state.detailLoading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=PulseAccent);val p=state.detail?:summary;PulseCard(p,state.now,state.offset){};PulseSection("Send a response"){OutlinedTextField(text,{value->text=value.take(2000);attempt=attempt?.forUpdatedMessage(text)},modifier=Modifier.fillMaxWidth(),label={Text("Write a message")},minLines=3,maxLines=6,enabled=!sending);if(state.error!=null)Text(state.error!!,color=PulseMuted);val expired=pulseRemainingSeconds(p.expiresAt,state.now,state.offset)==0L;ElectricButton(onClick={val body=text.trim();if(body.isNotEmpty()&&!sending){sending=true;val current=attempt?.forUpdatedMessage(body)?:PulseResponseSubmission.forMessage(body);attempt=current;scope.launch{try{val response=state.repo.respond(PulseResponsePayload(p.id,current.idempotencyKey,body));vm.pendingConversation.value=response.conversationId;state.error=null;onClose();vm.runTask{repeat(4){val exists=ApiClient.request("conversations").jsonArray.any{c->c.jsonObject.str("id")==response.conversationId};if(!exists)delay(750L*(it+1))};vm.refreshUnread()}}catch(e:Exception){state.error=prettyPulseError(e);if(e is ApiException&&e.retryAfterSeconds!=null)state.retryAt=System.currentTimeMillis()+e.retryAfterSeconds.coerceAtLeast(1)*1000}finally{sending=false}}}},Modifier.fillMaxWidth(),enabled=!sending&&!expired&&text.trim().isNotEmpty()&&System.currentTimeMillis()>=state.retryAt){if(sending)CircularProgressIndicator(Modifier.size(19.dp),color=Color.White)else Text("Continue to chat")};if(expired)Text("This Pulse has expired or is no longer available.",color=PulseMuted);Text("This opens the existing conversation flow. Message requests are never accepted automatically.",color=PulseMuted,style=MaterialTheme.typography.bodySmall)};Spacer(Modifier.height(30.dp))}}
