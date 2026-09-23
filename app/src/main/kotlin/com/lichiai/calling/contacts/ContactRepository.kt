package com.lichiai.calling.contacts

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.lichiai.calling.permission.CallPermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ContactRepositoryStats(
    val totalContacts: Int = 0,
    val phoneNumbersCount: Int = 0,
    val simContactsCount: Int = 0,
    val localContactsCount: Int = 0,
    val cloudContactsCount: Int = 0,
    val lastRefreshTime: Long = 0L,
    val isLoading: Boolean = false
)

class ContactRepository(
    private val context: Context,
    private val permissionManager: CallPermissionManager,
    private val aliasesRepository: ContactAliasesRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val providerReader = ContactProviderReader(context)
    private val simReader = SimContactReader(context)

    private var cachedContacts: List<ResolvedContact> = emptyList()
    private var contactIndex: ContactIndex = ContactIndex(emptyList())

    private val _stats = MutableStateFlow(ContactRepositoryStats())
    val stats: StateFlow<ContactRepositoryStats> = _stats.asStateFlow()

    private var contentObserver: ContentObserver? = null

    init {
        if (permissionManager.hasReadContacts()) {
            registerContentObserver()
        }
        scope.launch {
            aliasesRepository.aliasesFlow.collect { aliases ->
                mutex.withLock {
                    if (cachedContacts.isNotEmpty()) {
                        contactIndex = ContactIndex(cachedContacts, aliases)
                    }
                }
            }
        }
    }

    fun registerContentObserver() {
        if (contentObserver != null) return
        if (!permissionManager.hasReadContacts()) return
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                scope.launch {
                    refresh()
                }
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                contentObserver!!
            )
        }
    }

    fun destroy() {
        contentObserver?.let {
            runCatching { context.contentResolver.unregisterContentObserver(it) }
            contentObserver = null
        }
    }

    suspend fun loadAllContacts(forceRefresh: Boolean = false): List<ResolvedContact> = withContext(Dispatchers.IO) {
        if (!permissionManager.hasReadContacts()) {
            return@withContext emptyList()
        }

        mutex.withLock {
            if (cachedContacts.isNotEmpty() && !forceRefresh) {
                return@withLock cachedContacts
            }

            _stats.value = _stats.value.copy(isLoading = true)

            // 1. Read Device/Account Contacts
            val providerContacts = runCatching { providerReader.readContacts() }.getOrDefault(emptyList())

            // 2. Read SIM Contacts
            val simContacts = runCatching { simReader.readSimContacts() }.getOrDefault(emptyList())

            // 3. Merge and Deduplicate
            val mergedContacts = mergeContacts(providerContacts, simContacts)

            val aliases = aliasesRepository.getAliases()
            contactIndex = ContactIndex(mergedContacts, aliases)
            cachedContacts = mergedContacts

            val simCount = mergedContacts.count { it.isSimContact }
            val localCount = mergedContacts.count { it.isLocalContact }
            val cloudCount = mergedContacts.count { it.isCloudContact }
            val totalPhones = mergedContacts.sumOf { it.phoneNumbers.size }

            _stats.value = ContactRepositoryStats(
                totalContacts = mergedContacts.size,
                phoneNumbersCount = totalPhones,
                simContactsCount = simCount,
                localContactsCount = localCount,
                cloudContactsCount = cloudCount,
                lastRefreshTime = System.currentTimeMillis(),
                isLoading = false
            )

            mergedContacts
        }
    }

    suspend fun searchContacts(query: String): List<ContactCandidate> = withContext(Dispatchers.IO) {
        if (!permissionManager.hasReadContacts()) {
            return@withContext emptyList()
        }
        if (cachedContacts.isEmpty()) {
            loadAllContacts(forceRefresh = false)
        }
        contactIndex.search(query)
    }

    suspend fun findBestMatch(query: String): ContactCandidate? {
        findFastMatch(query)?.let { return it }
        val results = searchContacts(query)
        return results.firstOrNull()
    }

    /**
     * Non-blocking, zero-latency synchronous lookup against the memory-cached contact index.
     */
    fun findFastMatch(query: String): ContactCandidate? {
        if (cachedContacts.isEmpty()) return null
        return contactIndex.search(query).firstOrNull()
    }

    suspend fun refresh() {
        loadAllContacts(forceRefresh = true)
    }

    fun invalidateCache() {
        scope.launch {
            mutex.withLock {
                cachedContacts = emptyList()
                contactIndex = ContactIndex(emptyList())
            }
        }
    }

    private fun mergeContacts(
        providerContacts: List<ResolvedContact>,
        simContacts: List<ResolvedContact>
    ): List<ResolvedContact> {
        val contactMap = mutableMapOf<String, ResolvedContact>()
        val phoneIndex = mutableMapOf<String, String>() // normalized phone -> contact ID

        for (contact in providerContacts) {
            contactMap[contact.id] = contact
            for (phone in contact.phoneNumbers) {
                val norm = phone.normalizedNumber
                if (norm.isNotBlank()) {
                    phoneIndex[norm] = contact.id
                    val digits = norm.filter { it.isDigit() }
                    if (digits.length >= 10) {
                        phoneIndex[digits.takeLast(10)] = contact.id
                    }
                }
            }
        }

        for (simContact in simContacts) {
            val simPhone = simContact.phoneNumbers.firstOrNull()
            val normSimPhone = simPhone?.normalizedNumber ?: ""
            val digits = normSimPhone.filter { it.isDigit() }

            val existingContactId = if (normSimPhone.isNotBlank()) {
                phoneIndex[normSimPhone] ?: phoneIndex[digits.takeLast(10)]
            } else null

            if (existingContactId != null && contactMap.containsKey(existingContactId)) {
                // Merge SIM origin flag and sources into existing contact
                val existing = contactMap[existingContactId]!!
                val mergedSources = (existing.accountSources + simContact.accountSources).distinct()
                val updated = existing.copy(
                    isSimContact = true,
                    accountSources = mergedSources
                )
                contactMap[existingContactId] = updated
            } else {
                // Standalone SIM contact
                contactMap[simContact.id] = simContact
                if (normSimPhone.isNotBlank()) {
                    phoneIndex[normSimPhone] = simContact.id
                }
            }
        }

        return contactMap.values.toList()
    }
}
