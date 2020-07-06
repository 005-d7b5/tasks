/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import butterknife.BindView
import butterknife.OnClick
import com.todoroo.andlib.utility.DateUtilities
import com.todoroo.astrid.alarms.AlarmService
import dagger.hilt.android.AndroidEntryPoint
import org.tasks.R
import org.tasks.activities.DateAndTimePickerActivity
import org.tasks.date.DateTimeUtils
import org.tasks.dialogs.DialogBuilder
import org.tasks.dialogs.MyTimePickerDialog
import org.tasks.locale.Locale
import org.tasks.ui.TaskEditControlFragment
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Control set dealing with reminder settings
 *
 * @author Tim Su <tim></tim>@todoroo.com>
 */
@AndroidEntryPoint
class ReminderControlSet : TaskEditControlFragment() {
    @Inject lateinit var activity: Activity
    @Inject lateinit var alarmService: AlarmService
    @Inject lateinit var locale: Locale
    @Inject lateinit var dialogBuilder: DialogBuilder
    
    @BindView(R.id.alert_container)
    lateinit var alertContainer: LinearLayout

    @BindView(R.id.reminder_alarm)
    lateinit var mode: TextView
    
    private var randomControlSet: RandomReminderControlSet? = null

    override fun createView(savedInstanceState: Bundle?) {
        mode.paintFlags = mode.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        when {
            viewModel.ringNonstop!! -> setRingMode(2)
            viewModel.ringFiveTimes!! -> setRingMode(1)
            else -> setRingMode(0)
        }
        if (viewModel.whenDue!!) {
            addDue()
        }
        if (viewModel.whenOverdue!!) {
            addOverdue()
        }
        if (viewModel.reminderPeriod!! > 0) {
            addRandomReminder(viewModel.reminderPeriod!!)
        }
        viewModel.selectedAlarms?.forEach(this::addAlarmRow)
    }

    @OnClick(R.id.reminder_alarm)
    fun onClickRingType() {
        val modes = resources.getStringArray(R.array.reminder_ring_modes)
        val ringMode = when {
            viewModel.ringNonstop == true -> 2
            viewModel.ringFiveTimes == true -> 1
            else -> 0
        }
        dialogBuilder
                .newDialog()
                .setSingleChoiceItems(modes, ringMode) { dialog: DialogInterface, which: Int ->
                    setRingMode(which)
                    dialog.dismiss()
                }
                .show()
    }

    private fun setRingMode(ringMode: Int) {
        viewModel.ringNonstop = ringMode == 2
        viewModel.ringFiveTimes = ringMode == 1
        mode.setText(getRingModeString(ringMode))
    }

    @StringRes
    private fun getRingModeString(ringMode: Int): Int {
        return when (ringMode) {
            2 -> R.string.ring_nonstop
            1 -> R.string.ring_five_times
            else -> R.string.ring_once
        }
    }

    private fun addAlarm(selected: String) {
        when (selected) {
            getString(R.string.when_due) -> addDue()
            getString(R.string.when_overdue) -> addOverdue()
            getString(R.string.randomly) -> addRandomReminder(TimeUnit.DAYS.toMillis(14))
            getString(R.string.pick_a_date_and_time) -> addNewAlarm()
        }
    }

    @OnClick(R.id.alarms_add)
    fun addAlarm() {
        val options = options
        if (options.size == 1) {
            addNewAlarm()
        } else {
            dialogBuilder
                    .newDialog()
                    .setItems(options) { dialog: DialogInterface, which: Int ->
                        addAlarm(options[which])
                        dialog.dismiss()
                    }
                    .show()
        }
    }

    override val layout = R.layout.control_set_reminders

    override val icon = R.drawable.ic_outline_notifications_24px

    override fun controlId() = TAG

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_NEW_ALARM) {
            if (resultCode == Activity.RESULT_OK) {
                val timestamp = data!!.getLongExtra(MyTimePickerDialog.EXTRA_TIMESTAMP, 0L)
                if (viewModel.selectedAlarms?.add(timestamp) == true) {
                    addAlarmRow(timestamp)
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun addAlarmRow(timestamp: Long) {
        addAlarmRow(
                DateUtilities.getLongDateStringWithTime(timestamp, locale.locale),
                View.OnClickListener { viewModel.selectedAlarms?.remove(timestamp) })
    }

    private fun addNewAlarm() {
        val intent = Intent(activity, DateAndTimePickerActivity::class.java)
        intent.putExtra(
                DateAndTimePickerActivity.EXTRA_TIMESTAMP, DateTimeUtils.newDateTime().noon().millis)
        startActivityForResult(intent, REQUEST_NEW_ALARM)
    }

    private fun addAlarmRow(text: String, onRemove: View.OnClickListener): View {
        val alertItem = requireActivity().layoutInflater.inflate(R.layout.alarm_edit_row, null)
        alertContainer.addView(alertItem)
        addAlarmRow(alertItem, text, onRemove)
        return alertItem
    }

    private fun addAlarmRow(alertItem: View, text: String, onRemove: View.OnClickListener?) {
        val display = alertItem.findViewById<TextView>(R.id.alarm_string)
        display.text = text
        alertItem
                .findViewById<View>(R.id.clear)
                .setOnClickListener { v: View? ->
                    alertContainer.removeView(alertItem)
                    onRemove?.onClick(v)
                }
    }

    private val options: List<String>
        get() {
            val options: MutableList<String> = ArrayList()
            if (viewModel.whenDue != true) {
                options.add(getString(R.string.when_due))
            }
            if (viewModel.whenOverdue != true) {
                options.add(getString(R.string.when_overdue))
            }
            if (randomControlSet == null) {
                options.add(getString(R.string.randomly))
            }
            options.add(getString(R.string.pick_a_date_and_time))
            return options
        }

    private fun addDue() {
        viewModel.whenDue = true
        addAlarmRow(getString(R.string.when_due), View.OnClickListener {
            viewModel.whenDue = false
        })
    }

    private fun addOverdue() {
        viewModel.whenOverdue = true
        addAlarmRow(getString(R.string.when_overdue), View.OnClickListener {
            viewModel.whenOverdue = false
        })
    }

    private fun addRandomReminder(reminderPeriod: Long) {
        val alarmRow = addAlarmRow(getString(R.string.randomly_once) + " ", View.OnClickListener {
            viewModel.reminderPeriod = 0
            randomControlSet = null
        })
        randomControlSet = RandomReminderControlSet(activity, alarmRow, reminderPeriod, viewModel)
    }

    companion object {
        const val TAG = R.string.TEA_ctrl_reminders_pref
        private const val REQUEST_NEW_ALARM = 12152
    }
}