package com.lichiai.calling.contacts

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.RawContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactProviderReader(private val context: Context) {

    private val contentResolver: ContentResolver get() = context.contentResolver

    suspend fun readContacts(): List<ResolvedContact> = withContext(Dispatchers.IO) {
        val rawContactAccounts = queryRawContactAccounts()
        val contactsMap = mutableMapOf<Long, MutableContactBuilder>()

        val projection = arrayOf(
            Phone._ID,
            Phone.CONTACT_ID,
            Phone.RAW_CONTACT_ID,
            Phone.DISPLAY_NAME,
            Phone.NUMBER,
            Phone.NORMALIZED_NUMBER,
            Phone.TYPE,
            Phone.LABEL,
            Phone.IS_PRIMARY,
            Phone.PHOTO_URI,
            Phone.LOOKUP_KEY
        )

        val sortOrder = "${Phone.DISPLAY_NAME} ASC"

        runCatching {
            contentResolver.query(
                Phone.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(Phone._ID)
                val contactIdCol = cursor.getColumnIndex(Phone.CONTACT_ID)
                val rawContactIdCol = cursor.getColumnIndex(Phone.RAW_CONTACT_ID)
                val nameCol = cursor.getColumnIndex(Phone.DISPLAY_NAME)
                val numberCol = cursor.getColumnIndex(Phone.NUMBER)
                val normNumberCol = cursor.getColumnIndex(Phone.NORMALIZED_NUMBER)
                val typeCol = cursor.getColumnIndex(Phone.TYPE)
                val labelCol = cursor.getColumnIndex(Phone.LABEL)
                val isPrimaryCol = cursor.getColumnIndex(Phone.IS_PRIMARY)
                val photoCol = cursor.getColumnIndex(Phone.PHOTO_URI)
                val lookupCol = cursor.getColumnIndex(Phone.LOOKUP_KEY)

                while (cursor.moveToNext()) {
                    val contactId = if (contactIdCol >= 0) cursor.getLong(contactIdCol) else 0L
                    val rawContactId = if (rawContactIdCol >= 0) cursor.getLong(rawContactIdCol) else 0L
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                    val rawNumber = if (numberCol >= 0) cursor.getString(numberCol) ?: "" else ""
                    val normNumber = if (normNumberCol >= 0) cursor.getString(normNumberCol) ?: "" else ""
                    val type = if (typeCol >= 0) cursor.getInt(typeCol) else Phone.TYPE_OTHER
                    val label = if (labelCol >= 0) cursor.getString(labelCol) else null
                    val isPrimary = if (isPrimaryCol >= 0) cursor.getInt(isPrimaryCol) == 1 else false
                    val photoUri = if (photoCol >= 0) cursor.getString(photoCol) else null
                    val lookupKey = if (lookupCol >= 0) cursor.getString(lookupCol) else null

                    if (displayName.isBlank() && rawNumber.isBlank()) continue

                    val effectiveName = displayName.ifBlank { rawNumber }
                    val effectiveNorm = normNumber.ifBlank { PhoneNumberNormalizer.normalize(rawNumber) }

                    val phoneEntry = ContactPhoneNumber(
                        rawNumber = rawNumber,
                        normalizedNumber = effectiveNorm,
                        type = type,
                        label = label,
                        isPrimary = isPrimary,
                        source = "ContactsProvider"
                    )

                    val builder = contactsMap.getOrPut(contactId) {
                        MutableContactBuilder(
                            contactId = contactId,
                            displayName = effectiveName,
                            photoUri = photoUri,
                            lookupKey = lookupKey
                        )
                    }

                    builder.phoneNumbers.add(phoneEntry)
                    if (rawContactId > 0) {
                        builder.rawContactIds.add(rawContactId)
                        val accountSource = rawContactAccounts[rawContactId]
                        if (accountSource != null) {
                            builder.accountSources.add(accountSource)
                        }
                    }
                }
            }
        }

        contactsMap.values.map { it.build() }
    }

    private fun queryRawContactAccounts(): Map<Long, ContactSource> {
        val result = mutableMapOf<Long, ContactSource>()
        val projection = arrayOf(
            RawContacts._ID,
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE
        )

        runCatching {
            contentResolver.query(
                RawContacts.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(RawContacts._ID)
                val nameCol = cursor.getColumnIndex(RawContacts.ACCOUNT_NAME)
                val typeCol = cursor.getColumnIndex(RawContacts.ACCOUNT_TYPE)

                while (cursor.moveToNext()) {
                    val rawId = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val accName = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val accType = if (typeCol >= 0) cursor.getString(typeCol) else null

                    if (rawId > 0) {
                        val isSim = isSimAccountType(accType)
                        val isCloud = accType?.contains("google", ignoreCase = true) == true ||
                                accType?.contains("cloud", ignoreCase = true) == true
                        val isLocal = accType == null || accType.contains("local", ignoreCase = true)

                        result[rawId] = ContactSource(
                            accountName = accName,
                            accountType = accType,
                            isSim = isSim,
                            isCloud = isCloud,
                            isLocal = isLocal
                        )
                    }
                }
            }
        }
        return result
    }

    private fun isSimAccountType(accountType: String?): Boolean {
        if (accountType == null) return false
        val lower = accountType.lowercase()
        return lower.contains("sim") ||
                lower.contains("icc") ||
                lower.contains("adn") ||
                lower.contains("usim") ||
                lower == "vnd.sec.contact.sim" ||
                lower == "com.android.sim" ||
                lower == "com.android.contacts.sim" ||
                lower == "com.samsung.android.sim"
    }

    private class MutableContactBuilder(
        val contactId: Long,
        var displayName: String,
        var photoUri: String?,
        var lookupKey: String?
    ) {
        val rawContactIds = mutableSetOf<Long>()
        val phoneNumbers = mutableListOf<ContactPhoneNumber>()
        val accountSources = mutableSetOf<ContactSource>()

        fun build(): ResolvedContact {
            val isSim = accountSources.any { it.isSim }
            val isCloud = accountSources.any { it.isCloud }
            val isLocal = accountSources.any { it.isLocal } || (!isSim && !isCloud)

            // Deduplicate phone numbers
            val distinctNumbers = phoneNumbers.distinctBy { it.normalizedNumber.ifBlank { it.rawNumber } }

            return ResolvedContact(
                id = "cp_$contactId",
                aggregateContactId = contactId,
                rawContactIds = rawContactIds.toList(),
                displayName = displayName,
                normalizedName = ContactNormalizer.normalize(displayName),
                phoneNumbers = distinctNumbers,
                accountSources = accountSources.toList(),
                isSimContact = isSim,
                isLocalContact = isLocal,
                isCloudContact = isCloud,
                photoUri = photoUri,
                lookupKey = lookupKey
            )
        }
    }
}
