package org.tasks.ui.editviewmodel

import com.google.ical.values.RRule
import com.natpryce.makeiteasy.MakeItEasy.with
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tasks.injection.ProductionModule
import org.tasks.makers.TaskMaker
import org.tasks.makers.TaskMaker.newTask

@UninstallModules(ProductionModule::class)
@HiltAndroidTest
class RepeatTests : BaseTaskEditViewModelTest() {
    @Test
    fun changeRepeatAfterCompletion() = runBlocking {
        val task = newTask(with(TaskMaker.RRULE, RRule("RRULE:FREQ=DAILY;INTERVAL=1")))
        viewModel.setup(task)

        viewModel.repeatAfterCompletion = true

        save()

        assertEquals(
                "RRULE:FREQ=DAILY;INTERVAL=1;FROM=COMPLETION",
                taskDao.fetch(task.id)!!.recurrence)
    }

    @Test
    fun removeRepeatAfterCompletion() = runBlocking {
        val task = newTask()
        task.recurrence = "RRULE:FREQ=DAILY;INTERVAL=1;FROM=COMPLETION"
        viewModel.setup(task)

        viewModel.repeatAfterCompletion = false

        save()

        assertEquals(
                "RRULE:FREQ=DAILY;INTERVAL=1",
                taskDao.fetch(task.id)!!.recurrence)
    }
}