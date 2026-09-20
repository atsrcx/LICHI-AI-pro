package com.lichiai.calling.contacts

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SimContactReader(private val context: Context) {

    private val contentResolver: ContentResolver get() = context.contentResolver

    private val simUris = listOf(
        Uri.parse("content://icc/adn"),
        Uri.parse("content://sim/adn"),
        Uri.parse("content://icc/adn/subId/1"),
        Uri.parse("content://icc/adn/subId/2"),
        Uri.parse("content://icc1/adn"),
        Uri.parse("content://icc2/adn")
    )

    suspend fun readSimContacts(): List<ResolvedContact> = withContext(Dispatchers.IO) {
        val simContacts = mutableListOf<ResolvedContact>()
        val seenSignatures = mutableSetOf<String>()

        for (uri in simUris) {
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name").takeIf { it >= 0 }
                        ?: cursor.getColumnIndex("tag").takeIf { it >= 0 }
                        ?: cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME).takeIf { it >= 0 }
                    val numberIndex = cursor.getColumnIndex("number").takeIf { it >= 0 }
                        ?: cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER).takeIf { it >= 0 }
                    val idIndex = cursor.getColumnIndex("_id").takeIf { it >= 0 }

                    var localIndex = 0
                    while (cursor.moveToNext()) {
                        localIndex++
                        val id = idIndex?.let { cursor.getString(it) } ?: "sim_${uri.lastPathSegment}_$localIndex"
                        val name = nameIndex?.let { cursor.getString(it) }?.trim() ?: ""
                        val number = numberIndex?.let { cursor.getString(it) }?.trim() ?: ""

                        if (name.isBlank() && number.isBlank()) continue

                        val effectiveName = name.ifBlank { number }
                        val normalized = PhoneNumberNormalizer.normalize(number)
                        val signature = "${effectiveName.lowercase()}_$normalized"

                        if (seenSignatures.add(signature)) {
                            val phone = ContactPhoneNumber(
                                rawNumber = number,
                                normalizedNumber = normalized,
                                type = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                                label = "SIM",
                                isPrimary = true,
                                source = "SIM ($uri)"
                            )

                            simContacts.add(
                                ResolvedContact(
                                    id = "sim_$id",
                                    displayName = effectiveName,
                                    normalizedName = ContactNormalizer.normalize(effectiveName),
                                    phoneNumbers = listOf(phone),
                                    accountSources = listOf(
                                        ContactSource(
                                            accountName = "SIM",
                                            accountType = "com.android.sim",
                                            isSim = true,
                                            isLocal = false,
                                            isCloud = false
                                        )
                                    ),
                                    isSimContact = true,
                                    isLocalContact = false,
                                    isCloudContact = false
                                )
                            )
                        }
                    }
                }
            }
        }

        simContacts
    }
}
