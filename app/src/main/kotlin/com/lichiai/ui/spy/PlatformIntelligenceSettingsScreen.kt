package com.lichiai.ui.spy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lichiai.data.AppSettings
import com.lichiai.data.SettingsRepository
import com.lichiai.spy.apify.ApifyClient
import com.lichiai.spy.registry.SpyProviderEntity
import com.lichiai.spy.registry.SpyProviderRepository
import com.lichiai.ui.LichiVisualTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformIntelligenceSettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val appSettings by settingsRepository.settings.collectAsState(initial = AppSettings())

    val apifyClient = remember(appSettings.apifyApiToken) {
        ApifyClient { appSettings.apifyApiToken }
    }
    val providerRepo = remember(apifyClient) {
        SpyProviderRepository.getInstance(context, apifyClient)
    }

    val providersList by providerRepo.providersFlow.collectAsState(initial = emptyList())

    // Token & Connection Verification State
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var verifyingToken by remember { mutableStateOf(false) }
    var tokenVerificationStatus by remember { mutableStateOf<String?>(null) }
    var tokenVerificationSuccess by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(appSettings.apifyApiToken) {
        if (tokenInput.isEmpty()) tokenInput = appSettings.apifyApiToken
    }

    // Single Global Search State
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchingRemote by remember { mutableStateOf(false) }
    var searchOffset by remember { mutableIntStateOf(0) }
    var totalAvailableResults by remember { mutableIntStateOf(0) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // Loading / Action states per providerId
    var schemaLoadingProviders by remember { mutableStateOf(setOf<String>()) }
    var providerErrors by remember { mutableStateOf(mapOf<String, String>()) }

    fun triggerSearch(query: String, offset: Int = 0, isAppend: Boolean = false) {
        searchJob?.cancel()
        if (query.isBlank()) {
            isSearchingRemote = false
            searchError = null
            return
        }

        searchJob = scope.launch {
            if (!isAppend) delay(300) // Debounce on typing
            isSearchingRemote = true
            searchError = null

            val res = providerRepo.searchStore(
                query = query,
                limit = 20,
                offset = offset
            )

            if (res.isSuccess) {
                val page = res.getOrThrow()
                totalAvailableResults = page.total
                searchOffset = page.offset + page.items.size
            } else {
                searchError = res.exceptionOrNull()?.message ?: "Store search failed"
            }
            isSearchingRemote = false
        }
    }

    // Filter displayed list: local providers matching search query
    val displayedProviders = remember(providersList, searchQuery) {
        if (searchQuery.isBlank()) {
            providersList
        } else {
            val q = searchQuery.lowercase(Locale.ROOT)
            providersList.filter { p ->
                p.title.lowercase(Locale.ROOT).contains(q)
                    || p.actorName.lowercase(Locale.ROOT).contains(q)
                    || p.actorUsername.lowercase(Locale.ROOT).contains(q)
                    || p.description.lowercase(Locale.ROOT).contains(q)
                    || p.platformKeys.any { it.contains(q) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Platform Intelligence (#Spy)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        scope.launch {
                            providerRepo.clearDisabledCache()
                        }
                    }
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Cache", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "header_controls") {
                Spacer(Modifier.height(4.dp))

                // 1. Spy System Enabled Switch
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable #Spy Platform Intelligence",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Allows #Spy commands to look up public profile intelligence with user-configured providers.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = appSettings.spyEnabled,
                            onCheckedChange = { chk ->
                                scope.launch {
                                    settingsRepository.update { it.copy(spyEnabled = chk) }
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 2. Apify Token Configuration & Test Connection
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = LichiVisualTokens.BrandPurple,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Apify Execution Token",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = tokenInput,
                                onValueChange = {
                                    tokenInput = it
                                    scope.launch {
                                        settingsRepository.update { s -> s.copy(apifyApiToken = it) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                textStyle = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                            )
                            IconButton(
                                onClick = { tokenVisible = !tokenVisible },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (tokenVisible) "Hide token" else "Show token",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        verifyingToken = true
                                        tokenVerificationStatus = null
                                        val check = apifyClient.verifyToken()
                                        if (check.isSuccess) {
                                            val u = check.getOrThrow()
                                            tokenVerificationSuccess = true
                                            tokenVerificationStatus = "Connected: @${u.username} (${u.plan?.name ?: "Standard Plan"})"
                                        } else {
                                            tokenVerificationSuccess = false
                                            tokenVerificationStatus = check.exceptionOrNull()?.message ?: "Verification failed"
                                        }
                                        verifyingToken = false
                                    }
                                },
                                enabled = !verifyingToken && tokenInput.isNotBlank(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                if (verifyingToken) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Verifying...")
                                } else {
                                    Text("Test Connection")
                                }
                            }

                            tokenVerificationStatus?.let { status ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (tokenVerificationSuccess == true) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (tokenVerificationSuccess == true) LichiVisualTokens.StatusGreen else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (tokenVerificationSuccess == true) LichiVisualTokens.StatusGreen else MaterialTheme.colorScheme.error,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 3. ONE GLOBAL SEARCH BAR (Mandatory Section 3)
                Text(
                    text = "Configure Providers / Actors",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            searchOffset = 0
                            triggerSearch(it, offset = 0)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboardController?.hide()
                            triggerSearch(searchQuery, offset = 0)
                        }),
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        ),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search platform / actor / provider",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                searchQuery = ""
                                searchOffset = 0
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (isSearchingRemote) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Searching Apify Store...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (searchError != null) {
                    Text(
                        text = "Store search note: $searchError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Header stats: Enabled count & Total results
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val enabledCount = providersList.count { it.enabled }
                    Text(
                        text = "$enabledCount enabled provider(s)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = LichiVisualTokens.BrandPurple
                    )
                    if (totalAvailableResults > 0 && searchQuery.isNotBlank()) {
                        Text(
                            text = "Showing ${displayedProviders.size} of $totalAvailableResults",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (displayedProviders.isEmpty() && !isSearchingRemote) {
                item(key = "empty_state") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) "No providers cached yet.\nType a platform name (e.g. 'Instagram', 'Facebook', 'TikTok', 'Bluesky') in the search bar above."
                                else "No providers matching '$searchQuery'.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            items(displayedProviders, key = { it.canonicalActorId }) { provider ->
                val isLoadingSchema = schemaLoadingProviders.contains(provider.canonicalActorId)
                val errorMessage = providerErrors[provider.canonicalActorId]

                ProviderCardItem(
                    provider = provider,
                    isLoadingSchema = isLoadingSchema,
                    errorMessage = errorMessage,
                    onToggleEnabled = { enabled ->
                        scope.launch {
                            if (enabled) {
                                schemaLoadingProviders = schemaLoadingProviders + provider.canonicalActorId
                                providerErrors = providerErrors - provider.canonicalActorId
                                val res = providerRepo.enableProvider(provider.canonicalActorId)
                                if (res.isFailure) {
                                    providerErrors = providerErrors + (provider.canonicalActorId to (res.exceptionOrNull()?.message ?: "Schema unavailable"))
                                }
                                schemaLoadingProviders = schemaLoadingProviders - provider.canonicalActorId
                            } else {
                                providerRepo.disableProvider(provider.canonicalActorId)
                            }
                        }
                    },
                    onRefreshSchema = {
                        scope.launch {
                            schemaLoadingProviders = schemaLoadingProviders + provider.canonicalActorId
                            providerErrors = providerErrors - provider.canonicalActorId
                            val res = providerRepo.refreshSchema(provider.canonicalActorId)
                            if (res.isFailure) {
                                providerErrors = providerErrors + (provider.canonicalActorId to (res.exceptionOrNull()?.message ?: "Schema refresh failed"))
                            }
                            schemaLoadingProviders = schemaLoadingProviders - provider.canonicalActorId
                        }
                    }
                )
            }

            // Pagination Load More button
            if (totalAvailableResults > displayedProviders.size && searchQuery.isNotBlank()) {
                item(key = "load_more") {
                    OutlinedButton(
                        onClick = { triggerSearch(searchQuery, offset = searchOffset, isAppend = true) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Load More Results (${displayedProviders.size} of $totalAvailableResults)")
                    }
                }
            }

            item(key = "footer_spacer") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCardItem(
    provider: SpyProviderEntity,
    isLoadingSchema: Boolean,
    errorMessage: String?,
    onToggleEnabled: (Boolean) -> Unit,
    onRefreshSchema: () -> Unit
) {
    val context = LocalContext.current
    val hasSchema = provider.hasValidSchema()
    val isEnabled = provider.enabled

    val statusColor = when {
        isLoadingSchema -> MaterialTheme.colorScheme.primary
        isEnabled -> LichiVisualTokens.StatusGreen
        provider.runnableState == "UNRUNNABLE" -> MaterialTheme.colorScheme.error
        hasSchema -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusLabel = when {
        isLoadingSchema -> "Fetching Schema..."
        isEnabled -> "Enabled"
        provider.runnableState == "UNRUNNABLE" -> "Not runnable with current account"
        hasSchema -> "Schema Ready (Cached)"
        else -> "Discovered (Schema pending)"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = SolidColor(if (isEnabled) LichiVisualTokens.BrandPurple.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Avatar/Image, Title, Canonical ID, Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!provider.pictureUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(provider.pictureUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = provider.title,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = LichiVisualTokens.BrandPurple,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = provider.canonicalActorId,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (isLoadingSchema) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { onToggleEnabled(it) }
                    )
                }
            }

            // Description
            if (provider.description.isNotBlank()) {
                Text(
                    text = provider.description,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Platform Tags & Capabilities
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Platforms
                provider.platformKeys.forEach { plat ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = LichiVisualTokens.BrandPurple.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = plat.uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = LichiVisualTokens.BrandPurple,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Pricing
                val pricing = provider.pricingModel ?: "FREE"
                val priceText = if (provider.priceUsd != null && provider.priceUsd > 0) "$${provider.priceUsd}/mo" else pricing
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (pricing.equals("FREE", ignoreCase = true)) LichiVisualTokens.StatusGreen.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = priceText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (pricing.equals("FREE", ignoreCase = true)) LichiVisualTokens.StatusGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Capabilities summary
                provider.capabilities.take(3).forEach { cap ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = cap.lowercase(Locale.ROOT).replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Status, Health, & Refresh Schema
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = statusColor
                        )
                    }

                    if (provider.successCount > 0 || provider.failureCount > 0) {
                        val latency = if (provider.averageLatencyMs > 0) "${provider.averageLatencyMs}ms avg" else ""
                        Text(
                            text = "${provider.successCount} success / ${provider.failureCount} fail $latency".trim(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRefreshSchema,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Schema",
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Sync Schema", fontSize = 11.sp)
                }
            }

            if (errorMessage != null) {
                Text(
                    text = "Error: $errorMessage",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
