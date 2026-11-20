package za.co.aftrhrs.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import za.co.aftrhrs.data.ApiClient
import za.co.aftrhrs.data.ApiException
import java.net.URLEncoder

private data class BucketEntry(val id: String, val label: String, val shared: Boolean = false)

private fun JsonElement?.bucketEntries(): List<BucketEntry> =
    (this as? JsonArray)?.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val label = (item["label"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        BucketEntry(id, label, (item["shared"] as? JsonPrimitive)?.booleanOrNull == true)
    } ?: emptyList()

@Composable
fun BucketListProfileSection(userId: String, isOwnProfile: Boolean) {
    var items by remember(userId) { mutableStateOf<List<BucketEntry>>(emptyList()) }
    var sharedCount by remember(userId) { mutableIntStateOf(0) }
    LaunchedEffect(userId, isOwnProfile) {
        items = emptyList()
        try {
            val result = ApiClient.bucketListRequest(
                if (isOwnProfile) "?page=1&pageSize=1" else "?userId=${URLEncoder.encode(userId, "UTF-8")}"
            ).jsonObject
            items = (result[if (isOwnProfile) "selected" else "items"]).bucketEntries()
            sharedCount = (result["sharedCount"] as? JsonPrimitive)?.intOrNull ?: 0
        } catch (_: Exception) {
            // Respect backend Premium and profile visibility restrictions.
            items = emptyList()
        }
    }
    if (items.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF20182E), RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Bucket List", color = Color(0xFFCF9CFF), fontWeight = FontWeight.Bold)
            if (sharedCount > 0) Text("$sharedCount shared experiences", color = Color(0xFFCF9CFF))
            items.forEach { item ->
                Text((if (item.shared) "♥  " else "✦  ") + item.label, color = Color.White)
            }
        }
    }
}

@Composable
fun BucketListEditorSection(openPremium: () -> Unit) {
    var catalogue by remember { mutableStateOf<List<BucketEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<List<BucketEntry>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var maxItems by remember { mutableIntStateOf(10) }
    var submissionsEnabled by remember { mutableStateOf(false) }
    var suggestion by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var premiumRequired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(search, page, refresh) {
        busy = true
        try {
            val query = "?page=$page&pageSize=50&search=${URLEncoder.encode(search.take(120), "UTF-8")}"
            val data = ApiClient.bucketListRequest(query).jsonObject
            val batch = data["catalogue"].bucketEntries()
            catalogue = if (page == 1) batch else (catalogue + batch).distinctBy { it.id }
            selected = data["selected"].bucketEntries()
            val pagination = data["pagination"] as? JsonObject
            hasMore = (pagination?.get("hasMore") as? JsonPrimitive)?.booleanOrNull == true
            maxItems = (data["maxItems"] as? JsonPrimitive)?.intOrNull ?: 10
            submissionsEnabled = (data["submissionsEnabled"] as? JsonPrimitive)?.booleanOrNull == true
            premiumRequired = false
            message = null
        } catch (e: ApiException) {
            premiumRequired = e.status == 403 && e.code == "PREMIUM_REQUIRED"
            message = if (premiumRequired) "Bucket List requires Premium." else (e.message ?: "Unable to load Bucket List")
        } catch (e: Exception) {
            message = e.message ?: "Unable to load Bucket List"
        } finally { busy = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Bucket List · Premium", color = Color(0xFFCF9CFF), fontWeight = FontWeight.Bold)
        Text("${selected.size} of $maxItems experiences selected", color = Color.White)
        if (premiumRequired) OutlinedButton(onClick = openPremium) { Text("Explore Premium") }
        if (!premiumRequired) OutlinedTextField(search, { search = it; page = 1 }, label = { Text("Search experiences") }, modifier = Modifier.fillMaxWidth())
        if (!premiumRequired) selected.forEach { item ->
            Text("✓ ${item.label}", color = Color(0xFFCF9CFF))
        }
        if (!premiumRequired) catalogue.forEach { item ->
            val chosen = selected.any { it.id == item.id }
            OutlinedButton(onClick = {
                busy = true
                scope.launch {
                    try {
                        ApiClient.bucketListRequest(
                            if (chosen) "?itemId=${item.id}" else "",
                            if (chosen) HttpMethod.Delete else HttpMethod.Post,
                            if (chosen) null else buildJsonObject { put("itemId", item.id) }
                        )
                        refresh++
                    } catch (e: ApiException) {
                        premiumRequired = e.status == 403 && e.code == "PREMIUM_REQUIRED"
                        message = if (premiumRequired) "Bucket List requires Premium." else (e.message ?: "Unable to update selection")
                    } catch (e: Exception) {
                        message = e.message ?: "Unable to update selection"
                    } finally { busy = false }
                }
            }, enabled = !busy && !premiumRequired && (chosen || selected.size < maxItems), modifier = Modifier.fillMaxWidth()) {
                Text((if (chosen) "✓  " else "+  ") + item.label)
            }
        }
        if (hasMore && !premiumRequired) TextButton(onClick = { page++ }, enabled = !busy) { Text("Load more") }
        if (submissionsEnabled && !premiumRequired) {
            OutlinedTextField(suggestion, { suggestion = it.take(120) }, label = { Text("Suggest an experience") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = ApiClient.bucketListRequest("submissions", HttpMethod.Post,
                            buildJsonObject { put("label", suggestion.trim()) }).jsonObject
                        message = if ((result["status"] as? JsonPrimitive)?.contentOrNull == "PENDING")
                            "Submitted for approval" else "Suggestion approved"
                        suggestion = ""
                        refresh++
                    } catch (e: ApiException) { premiumRequired = e.status == 403 && e.code == "PREMIUM_REQUIRED"; message = if (premiumRequired) "Bucket List requires Premium." else (e.message ?: "Suggestion failed") }
                    catch (e: Exception) { message = e.message ?: "Suggestion failed" }
                    finally { busy = false }
                }
            }, enabled = !busy && suggestion.trim().length in 3..120) { Text("Suggest for approval") }
        }
        message?.let { Text(it, color = Color(0xFFFFB4AB)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
