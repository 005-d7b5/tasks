package org.tasks.data

import android.content.ContentProviderOperation
import android.content.ContentProviderOperation.*
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import at.bitfire.ical4android.UnknownProperty
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.fortuna.ical4j.model.property.XProperty
import org.dmfs.tasks.contract.TaskContract.*
import org.dmfs.tasks.contract.TaskContract.Property.Category
import org.dmfs.tasks.contract.TaskContract.Property.Relation
import org.json.JSONObject
import org.tasks.R
import org.tasks.caldav.iCalendar.Companion.APPLE_SORT_ORDER
import timber.log.Timber
import javax.inject.Inject

class OpenTaskDao @Inject constructor(
        @ApplicationContext context: Context,
        private val caldavDao: CaldavDao
) {
    private val cr = context.contentResolver
    val authority = context.getString(R.string.opentasks_authority)
    private val tasks = Tasks.getContentUri(authority)
    private val properties = Properties.getContentUri(authority)

    suspend fun newAccounts(): List<String> =
            getListsByAccount()
                    .newAccounts(caldavDao)
                    .map { it.key }

    suspend fun getListsByAccount(): Map<String, List<CaldavCalendar>> =
            getLists().groupBy { it.account!! }

    suspend fun getLists(): List<CaldavCalendar> = withContext(Dispatchers.IO) {
        val calendars = ArrayList<CaldavCalendar>()
        cr.query(
                TaskLists.getContentUri(authority),
                null,
                "${TaskListColumns.SYNC_ENABLED}=1 AND ($ACCOUNT_TYPE = '$ACCOUNT_TYPE_DAVx5' OR $ACCOUNT_TYPE = '$ACCOUNT_TYPE_ETESYNC')",
                null,
                null)?.use {
            while (it.moveToNext()) {
                val accountType = it.getString(TaskLists.ACCOUNT_TYPE)
                val accountName = it.getString(TaskLists.ACCOUNT_NAME)
                calendars.add(CaldavCalendar().apply {
                    id = it.getLong(TaskLists._ID)
                    account = "$accountType:$accountName"
                    name = it.getString(TaskLists.LIST_NAME)
                    color = it.getInt(TaskLists.LIST_COLOR)
                    url = it.getString(CommonSyncColumns._SYNC_ID)
                    ctag = it.getString(TaskLists.SYNC_VERSION)
                            ?.let(::JSONObject)
                            ?.getString("value")
                })
            }
        }
        calendars
    }

    suspend fun getEtags(listId: Long): List<Triple<String, String?, String>> = withContext(Dispatchers.IO) {
        val items = ArrayList<Triple<String, String?, String>>()
        cr.query(
                tasks,
                arrayOf(Tasks._SYNC_ID, Tasks.SYNC1, "version"),
                "${Tasks.LIST_ID} = $listId",
                null,
                null)?.use {
            while (it.moveToNext()) {
                items.add(Triple(
                        it.getString(Tasks._SYNC_ID)!!,
                        it.getString(Tasks.SYNC1),
                        it.getLong("version").toString()))
            }
        }
        items
    }

    fun delete(listId: Long, item: String): ContentProviderOperation =
            newDelete(tasks)
                    .withSelection(
                            "${Tasks.LIST_ID} = $listId AND ${Tasks._SYNC_ID} = '$item'",
                            null)
                    .build()

    fun insert(values: ContentValues): ContentProviderOperation =
            newInsert(tasks)
                    .withValues(values)
                    .build()

    fun update(listId: Long, item: String, values: ContentValues): ContentProviderOperation =
            newUpdate(tasks)
                    .withSelection(
                            "${Tasks.LIST_ID} = $listId AND ${Tasks._SYNC_ID} = '$item'",
                            null)
                    .withValues(values)
                    .build()

    suspend fun getId(listId: Long, uid: String?): Long? =
            uid
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        withContext(Dispatchers.IO) {
                            cr.query(
                                    tasks,
                                    arrayOf(Tasks._ID),
                                    "${Tasks.LIST_ID} = $listId AND ${Tasks._UID} = '$uid'",
                                    null,
                                    null)?.use {
                                if (it.moveToFirst()) {
                                    it.getLong(Tasks._ID)
                                } else {
                                    null
                                }
                            }
                        }
                    }
                    ?: Timber.e("No task with uid=$uid").let { null }

    suspend fun batch(operations: List<ContentProviderOperation>) = withContext(Dispatchers.IO) {
        operations.chunked(OPENTASK_BATCH_LIMIT).forEach {
            cr.applyBatch(authority, ArrayList(it))
        }
    }

    suspend fun getTags(listId: Long, caldavTask: CaldavTask): List<String> = withContext(Dispatchers.IO) {
        val id = getId(listId, caldavTask.remoteId)
        val tags = ArrayList<String>()
        cr.query(
                properties,
                arrayOf(Properties.DATA1),
                "${Properties.TASK_ID} = $id AND ${Properties.MIMETYPE} = '${Category.CONTENT_ITEM_TYPE}'",
                null,
                null)?.use {
            while (it.moveToNext()) {
                it.getString(Properties.DATA1)?.let(tags::add)
            }
        }
        return@withContext tags
    }

    fun setTags(id: Long, tags: List<String>): List<ContentProviderOperation> {
        val delete = listOf(
                newDelete(properties)
                        .withSelection(
                                "${Properties.TASK_ID} = $id AND ${Properties.MIMETYPE} = '${Category.CONTENT_ITEM_TYPE}'",
                                null)
                        .build())
        val inserts = tags.map {
            newInsert(properties)
                    .withValues(ContentValues().apply {
                        put(Category.MIMETYPE, Category.CONTENT_ITEM_TYPE)
                        put(Category.TASK_ID, id)
                        put(Category.CATEGORY_NAME, it)
                    })
                    .build()
        }
        return delete + inserts
    }

    suspend fun getRemoteOrder(listId: Long, caldavTask: CaldavTask): Long? = withContext(Dispatchers.IO) {
        val id = getId(listId, caldavTask.remoteId) ?: return@withContext null
        cr.query(
                properties,
                arrayOf(Properties.DATA0),
                "${Properties.TASK_ID} = $id AND ${Properties.MIMETYPE} = '${UnknownProperty.CONTENT_ITEM_TYPE}' AND ${Properties.DATA0} LIKE '%$APPLE_SORT_ORDER%'",
                null,
                null)?.use {
            while (it.moveToNext()) {
                it.getString(Properties.DATA0)
                        ?.let(UnknownProperty::fromJsonString)
                        ?.takeIf { xprop -> xprop.name.equals(APPLE_SORT_ORDER, true) }
                        ?.let { xprop ->
                            return@withContext xprop.value.toLong()
                        }
            }
        }
        return@withContext null
    }

    fun setRemoteOrder(id: Long, caldavTask: CaldavTask): List<ContentProviderOperation> {
        val operations = ArrayList<ContentProviderOperation>()
        operations.add(
                newDelete(properties)
                        .withSelection(
                                "${Properties.TASK_ID} = $id AND ${Properties.MIMETYPE} = '${UnknownProperty.CONTENT_ITEM_TYPE}' AND ${Properties.DATA0} LIKE '%$APPLE_SORT_ORDER%'",
                                null)
                        .build())
        caldavTask.order?.let {
            operations.add(
                    newInsert(properties)
                            .withValues(ContentValues().apply {
                                put(Properties.MIMETYPE, UnknownProperty.CONTENT_ITEM_TYPE)
                                put(Properties.TASK_ID, id)
                                put(Properties.DATA0, UnknownProperty.toJsonString(XProperty(APPLE_SORT_ORDER, it.toString())))
                            })
                            .build())
        }
        return operations
    }

    fun updateParent(id: Long, parent: Long?): List<ContentProviderOperation> {
        val operations = ArrayList<ContentProviderOperation>()
        operations.add(
                newDelete(properties)
                        .withSelection(
                                "${Properties.TASK_ID} = $id AND ${Properties.MIMETYPE} = '${Relation.CONTENT_ITEM_TYPE}' AND ${Relation.RELATED_TYPE} = ${Relation.RELTYPE_PARENT}",
                                null
                        )
                        .build())
        parent?.let {
            operations.add(
                    newInsert(properties)
                            .withValues(ContentValues().apply {
                                put(Relation.MIMETYPE, Relation.CONTENT_ITEM_TYPE)
                                put(Relation.TASK_ID, id)
                                put(Relation.RELATED_TYPE, Relation.RELTYPE_PARENT)
                                put(Relation.RELATED_ID, parent)
                            })
                            .build())
        }
        return operations
    }

    suspend fun getParent(id: Long): String? = withContext(Dispatchers.IO) {
        cr.query(
                properties,
                arrayOf(Relation.RELATED_UID),
                "${Relation.TASK_ID} = $id AND ${Properties.MIMETYPE} = '${Relation.CONTENT_ITEM_TYPE}' AND ${Relation.RELATED_TYPE} = ${Relation.RELTYPE_PARENT}",
                null,
                null)?.use {
            if (it.moveToFirst()) {
                it.getString(Relation.RELATED_UID)
            } else {
                null
            }
        }
    }

    companion object {
        private const val OPENTASK_BATCH_LIMIT = 499
        const val ACCOUNT_TYPE_DAVx5 = "bitfire.at.davdroid"
        const val ACCOUNT_TYPE_ETESYNC = "com.etesync.syncadapter"

        suspend fun Map<String, List<CaldavCalendar>>.newAccounts(caldavDao: CaldavDao) =
                filterNot { (_, lists) -> caldavDao.anyExist(lists.map { it.url!! }) }

        fun Cursor.getString(columnName: String): String? =
                getString(getColumnIndex(columnName))

        fun Cursor.getInt(columnName: String): Int =
                getInt(getColumnIndex(columnName))

        fun Cursor.getLong(columnName: String): Long =
                getLong(getColumnIndex(columnName))
    }
}