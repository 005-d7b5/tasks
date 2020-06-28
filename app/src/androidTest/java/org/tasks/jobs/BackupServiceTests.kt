/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package org.tasks.jobs

import android.net.Uri
import androidx.test.InstrumentationRegistry
import com.todoroo.astrid.dao.TaskDaoBlocking
import com.todoroo.astrid.data.Task
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.tasks.R
import org.tasks.backup.TasksJsonExporter
import org.tasks.backup.TasksJsonExporter.ExportType
import org.tasks.injection.InjectingTestCase
import org.tasks.injection.ProductionModule
import org.tasks.preferences.Preferences
import java.io.File
import java.io.IOException
import javax.inject.Inject

@UninstallModules(ProductionModule::class)
@HiltAndroidTest
class BackupServiceTests : InjectingTestCase() {
    @Inject lateinit var jsonExporter: TasksJsonExporter
    @Inject lateinit var taskDao: TaskDaoBlocking
    @Inject lateinit var preferences: Preferences
    private lateinit var temporaryDirectory: File

    @Before
    override fun setUp() {
        super.setUp()
        temporaryDirectory = try {
            File.createTempFile("backup", System.nanoTime().toString())
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        if (!temporaryDirectory.delete()) {
            throw RuntimeException(
                    "Could not delete temp file: " + temporaryDirectory.absolutePath)
        }
        if (!temporaryDirectory.mkdir()) {
            throw RuntimeException(
                    "Could not create temp directory: " + temporaryDirectory.absolutePath)
        }
        preferences.setUri(R.string.p_backup_dir, Uri.fromFile(temporaryDirectory))

        // make a temporary task
        val task = Task()
        task.title = "helicopter"
        taskDao.createNew(task)
    }

    @After
    fun tearDown() {
        for (file in temporaryDirectory.listFiles()!!) {
            file.delete()
        }
        temporaryDirectory.delete()
    }

    @Test
    fun testBackup() {
        assertEquals(0, temporaryDirectory.list()!!.size)
        jsonExporter.exportTasks(InstrumentationRegistry.getTargetContext(), ExportType.EXPORT_TYPE_SERVICE, null)

        // assert file created
        val files = temporaryDirectory.listFiles()
        assertEquals(1, files!!.size)
        assertTrue(files[0].name.matches(BackupWork.BACKUP_FILE_NAME_REGEX))
    }
}