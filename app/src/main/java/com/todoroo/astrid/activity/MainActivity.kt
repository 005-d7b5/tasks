/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.view.ActionMode
import androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener
import androidx.lifecycle.lifecycleScope
import com.todoroo.andlib.utility.AndroidUtilities
import com.todoroo.astrid.activity.TaskEditFragment.Companion.newTaskEditFragment
import com.todoroo.astrid.activity.TaskListFragment.TaskListFragmentCallbackHandler
import com.todoroo.astrid.api.Filter
import com.todoroo.astrid.dao.TaskDao
import com.todoroo.astrid.data.Task
import com.todoroo.astrid.service.TaskCreator
import com.todoroo.astrid.timers.TimerControlSet.TimerControlSetCallback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.tasks.BuildConfig
import org.tasks.LocalBroadcastManager
import org.tasks.R
import org.tasks.activities.TagSettingsActivity
import org.tasks.billing.Inventory
import org.tasks.data.AlarmDao
import org.tasks.data.LocationDao
import org.tasks.data.Place
import org.tasks.data.TagDataDao
import org.tasks.databinding.TaskListActivityBinding
import org.tasks.dialogs.SortDialog.SortDialogCallback
import org.tasks.dialogs.WhatsNewDialog
import org.tasks.filters.PlaceFilter
import org.tasks.fragments.CommentBarFragment.CommentBarFragmentCallback
import org.tasks.gtasks.PlayServices
import org.tasks.injection.InjectingAppCompatActivity
import org.tasks.intents.TaskIntents
import org.tasks.location.LocationPickerActivity
import org.tasks.preferences.DefaultFilterProvider
import org.tasks.preferences.Preferences
import org.tasks.tasklist.ActionUtils
import org.tasks.themes.ColorProvider
import org.tasks.themes.Theme
import org.tasks.themes.ThemeColor
import org.tasks.ui.DeadlineControlSet.DueDateChangeListener
import org.tasks.ui.EmptyTaskEditFragment.Companion.newEmptyTaskEditFragment
import org.tasks.ui.ListFragment.OnListChanged
import org.tasks.ui.NavigationDrawerFragment
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : InjectingAppCompatActivity(), TaskListFragmentCallbackHandler, OnListChanged, TimerControlSetCallback, DueDateChangeListener, CommentBarFragmentCallback, SortDialogCallback {
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var defaultFilterProvider: DefaultFilterProvider
    @Inject lateinit var theme: Theme
    @Inject lateinit var taskDao: TaskDao
    @Inject lateinit var localBroadcastManager: LocalBroadcastManager
    @Inject lateinit var taskCreator: TaskCreator
    @Inject lateinit var playServices: PlayServices
    @Inject lateinit var inventory: Inventory
    @Inject lateinit var colorProvider: ColorProvider
    @Inject lateinit var locationDao: LocationDao
    @Inject lateinit var tagDataDao: TagDataDao
    @Inject lateinit var alarmDao: AlarmDao

    private lateinit var navigationDrawer: NavigationDrawerFragment
    private var currentNightMode = 0
    private var currentPro = false
    private var filter: Filter? = null
    private var actionMode: ActionMode? = null
    private lateinit var binding: TaskListActivityBinding

    /** @see android.app.Activity.onCreate
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.applyTheme(this)
        currentNightMode = nightMode
        currentPro = inventory.hasPro()
        binding = TaskListActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (savedInstanceState != null) {
            filter = savedInstanceState.getParcelable(EXTRA_FILTER)
            applyTheme()
        }
        navigationDrawer = navigationDrawerFragment
        navigationDrawer.setUp(binding.drawerLayout)
        binding.drawerLayout.addDrawerListener(
                object : SimpleDrawerListener() {
                    override fun onDrawerStateChanged(newState: Int) {
                        finishActionMode()
                    }
                })
        handleIntent()
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == NavigationDrawerFragment.REQUEST_SETTINGS) {
            if (AndroidUtilities.atLeastNougat()) {
                recreate()
            } else {
                finish()
                startActivity(TaskIntents.getTaskListIntent(this, filter))
            }
        } else if (requestCode == NavigationDrawerFragment.REQUEST_NEW_LIST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val filter: Filter? = data.getParcelableExtra(OPEN_FILTER)
                if (filter != null) {
                    startActivity(TaskIntents.getTaskListIntent(this, filter))
                }
            }
        } else if (requestCode == NavigationDrawerFragment.REQUEST_NEW_PLACE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val place: Place? = data.getParcelableExtra(LocationPickerActivity.EXTRA_PLACE)
                if (place != null) {
                    startActivity(TaskIntents.getTaskListIntent(this, PlaceFilter(place)))
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(EXTRA_FILTER, filter)
    }

    private fun clearUi() {
        finishActionMode()
        navigationDrawer.closeDrawer()
    }

    private suspend fun getTaskToLoad(filter: Filter?): Task? {
        val intent = intent
        if (intent.isFromHistory) {
            return null
        }
        if (intent.hasExtra(CREATE_TASK)) {
            intent.removeExtra(CREATE_TASK)
            return taskCreator.createWithValues(filter, "")
        }
        if (intent.hasExtra(OPEN_TASK)) {
            val task: Task? = intent.getParcelableExtra(OPEN_TASK)
            intent.removeExtra(OPEN_TASK)
            return task
        }
        return null
    }

    private fun openTask(filter: Filter?) = lifecycleScope.launch {
        val task = getTaskToLoad(filter)
        when {
            task != null -> onTaskListItemClicked(task)
            taskEditFragment == null -> hideDetailFragment()
            else -> showDetailFragment()
        }
    }

    private fun handleIntent() {
        val intent = intent
        val openFilter = intent.getFilter
        val loadFilter = intent.getFilterString
        val openTask = !intent.isFromHistory
                && (intent.hasExtra(OPEN_TASK) || intent.hasExtra(CREATE_TASK))
        val tef = taskEditFragment
        Timber.d("""
            
            **********
            broughtToFront: ${intent.broughtToFront}
            isFromHistory: ${intent.isFromHistory}
            flags: ${intent.flagsToString}
            OPEN_FILTER: ${openFilter?.let { "${it.listingTitle}: $it" }}
            LOAD_FILTER: $loadFilter
            OPEN_TASK: ${intent.getParcelableExtra<Task>(OPEN_TASK)}
            CREATE_TASK: ${intent.hasExtra(CREATE_TASK)}
            taskListFragment: ${taskListFragment?.getFilter()?.let { "${it.listingTitle}: $it" }}
            taskEditFragment: ${taskEditFragment?.editViewModel?.task}
            **********""")
        if (!openTask && (openFilter != null || !loadFilter.isNullOrBlank())) {
            tef?.let {
                lifecycleScope.launch {
                    it.save()
                }
            }
        }
        if (!loadFilter.isNullOrBlank() || openFilter == null && filter == null) {
            lifecycleScope.launch {
                val filter = if (loadFilter.isNullOrBlank()) {
                    defaultFilterProvider.getStartupFilter()
                } else {
                    defaultFilterProvider.getFilterFromPreference(loadFilter)
                }
                clearUi()
                if (isSinglePaneLayout) {
                    if (openTask) {
                        setFilter(filter)
                        openTask(filter)
                    } else {
                        openTaskListFragment(filter, true)
                    }
                } else {
                    openTaskListFragment(filter, true)
                    openTask(filter)
                }
            }
        } else if (openFilter != null) {
            clearUi()
            if (isSinglePaneLayout) {
                if (openTask) {
                    setFilter(openFilter)
                    openTask(openFilter)
                } else {
                    openTaskListFragment(openFilter, true)
                }
            } else {
                openTaskListFragment(openFilter, true)
                openTask(openFilter)
            }
        } else {
            val existing = taskListFragment
            val target = if (existing == null || existing.getFilter() !== filter) {
                TaskListFragment.newTaskListFragment(applicationContext, filter)
            } else {
                existing
            }
            if (isSinglePaneLayout) {
                if (openTask || tef != null) {
                    openTask(filter)
                } else {
                    openTaskListFragment(filter, false)
                }
            } else {
                openTaskListFragment(target, false)
                openTask(filter)
            }
        }
        if (intent.hasExtra(TOKEN_CREATE_NEW_LIST_NAME)) {
            val listName = intent.getStringExtra(TOKEN_CREATE_NEW_LIST_NAME)
            intent.removeExtra(TOKEN_CREATE_NEW_LIST_NAME)
            val activityIntent = Intent(this@MainActivity, TagSettingsActivity::class.java)
            activityIntent.putExtra(TagSettingsActivity.TOKEN_AUTOPOPULATE_NAME, listName)
            startActivityForResult(activityIntent, NavigationDrawerFragment.REQUEST_NEW_LIST)
        }
    }

    private fun showDetailFragment() {
        if (isSinglePaneLayout) {
            binding.detail.visibility = View.VISIBLE
            binding.master.visibility = View.GONE
        }
    }

    private fun hideDetailFragment() {
        filter?.let {
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.detail, newEmptyTaskEditFragment(it))
                    .runOnCommit {
                        if (isSinglePaneLayout) {
                            binding.master.visibility = View.VISIBLE
                            binding.detail.visibility = View.GONE
                        }
                    }
                    .commitNow()
        }
    }

    private fun setFilter(newFilter: Filter?) {
        filter = newFilter
        applyTheme()
    }

    private fun openTaskListFragment(filter: Filter?, force: Boolean = false) {
        openTaskListFragment(TaskListFragment.newTaskListFragment(applicationContext, filter), force)
    }

    private fun openTaskListFragment(taskListFragment: TaskListFragment, force: Boolean) {
        AndroidUtilities.assertMainThread()
        if (supportFragmentManager.isDestroyed) {
            return
        }
        val newFilter = taskListFragment.getFilter()
        if (filter != null
                && !force
                && filter!!.areItemsTheSame(newFilter)
                && filter!!.areContentsTheSame(newFilter)) {
            return
        }
        filter = newFilter
        navigationDrawer.setSelected(filter)
        defaultFilterProvider.lastViewedFilter = newFilter
        applyTheme()
        supportFragmentManager
                .beginTransaction()
                .replace(R.id.master, taskListFragment, FRAG_TAG_TASK_LIST)
                .commitNow()
    }

    private fun applyTheme() {
        val filterColor = filterColor
        filterColor.setStatusBarColor(binding.drawerLayout)
        filterColor.applyToNavigationBar(this)
        filterColor.applyTaskDescription(this, filter?.listingTitle ?: getString(R.string.app_name))
        theme.withThemeColor(filterColor).applyToContext(this)
    }

    private val filterColor: ThemeColor
        get() = if (filter != null && filter!!.tint != 0) colorProvider.getThemeColor(filter!!.tint, true) else theme.themeColor

    private val navigationDrawerFragment: NavigationDrawerFragment
        get() = supportFragmentManager
                .findFragmentById(NavigationDrawerFragment.FRAGMENT_NAVIGATION_DRAWER) as NavigationDrawerFragment

    override fun onResume() {
        super.onResume()
        if (currentNightMode != nightMode || currentPro != inventory.hasPro()) {
            recreate()
            return
        }
        if (preferences.getBoolean(R.string.p_just_updated, false)) {
            if (preferences.getBoolean(R.string.p_show_whats_new, true)) {
                val fragmentManager = supportFragmentManager
                if (fragmentManager.findFragmentByTag(FRAG_TAG_WHATS_NEW) == null) {
                    WhatsNewDialog().show(fragmentManager, FRAG_TAG_WHATS_NEW)
                }
            }
            preferences.setBoolean(R.string.p_just_updated, false)
        }
    }

    private val nightMode: Int
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    override suspend fun onTaskListItemClicked(task: Task?) {
        AndroidUtilities.assertMainThread()
        if (task == null) {
            return
        }
        taskEditFragment?.let {
            it.editViewModel.cleared.removeObservers(this@MainActivity)
            it.save()
        }
        clearUi()
        coroutineScope {
            val freshTask = async { if (task.isNew) task else taskDao.fetch(task.id) ?: task }
            val list = async { defaultFilterProvider.getList(task) }
            val location = async { locationDao.getLocation(task, preferences) }
            val tags = async { tagDataDao.getTags(task) }
            val alarms = async { alarmDao.getAlarms(task) }
            val fragment = withContext(Dispatchers.Default) {
                newTaskEditFragment(
                        freshTask.await(),
                        list.await(),
                        location.await(),
                        tags.await(),
                        alarms.await(),
                        filterColor)
            }
            supportFragmentManager.beginTransaction()
                    .replace(R.id.detail, fragment, TaskEditFragment.TAG_TASKEDIT_FRAGMENT)
                    .runOnCommit { showDetailFragment() }
                    .commitNow()

        }
    }

    override fun onNavigationIconClicked() {
        hideKeyboard()
        navigationDrawer.openDrawer()
    }

    override fun onBackPressed() {
        if (navigationDrawer.isDrawerOpen) {
            navigationDrawer.closeDrawer()
            return
        }
        taskEditFragment?.let {
            if (preferences.backButtonSavesTask()) {
                lifecycleScope.launch {
                    it.save()
                }
            } else {
                it.discardButtonClick()
            }
            return@onBackPressed
        }
        if (taskListFragment?.collapseSearchView() == true) {
            return
        }
        finish()
    }

    val taskListFragment: TaskListFragment?
        get() = supportFragmentManager.findFragmentByTag(FRAG_TAG_TASK_LIST) as TaskListFragment?

    val taskEditFragment: TaskEditFragment?
        get() = supportFragmentManager.findFragmentByTag(TaskEditFragment.TAG_TASKEDIT_FRAGMENT) as TaskEditFragment?

    override suspend fun stopTimer(): Task {
        return taskEditFragment!!.stopTimer()
    }

    override suspend fun startTimer(): Task {
        return taskEditFragment!!.startTimer()
    }

    private val isSinglePaneLayout: Boolean
        get() = !resources.getBoolean(R.bool.two_pane_layout)

    fun removeTaskEditFragment() {
        val removeTask = intent.removeTask
        val finishAffinity = intent.finishAffinity
        if (finishAffinity || taskListFragment == null) {
            finishAffinity()
        } else {
            if (removeTask && intent.broughtToFront) {
                moveTaskToBack(true)
            }
            hideKeyboard()
            hideDetailFragment()
            taskListFragment?.let {
                setFilter(it.getFilter())
                it.loadTaskListContent()
            }
        }
    }

    private fun hideKeyboard() {
        val view = currentFocus
        if (view != null) {
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun addComment(message: String?, picture: Uri?) {
        val taskEditFragment = taskEditFragment
        taskEditFragment?.addComment(message, picture)
    }

    override fun sortChanged(reload: Boolean) {
        taskListFragment?.clearCollapsed()
        localBroadcastManager.broadcastRefresh()
        if (reload) {
            openTaskListFragment(filter, true)
        }
    }

    override fun onSupportActionModeStarted(mode: ActionMode) {
        super.onSupportActionModeStarted(mode)
        actionMode = mode
        val filterColor = filterColor
        ActionUtils.applySupportActionModeColor(filterColor, mode)
        filterColor.setStatusBarColor(this)
    }

    @SuppressLint("NewApi")
    override fun onSupportActionModeFinished(mode: ActionMode) {
        super.onSupportActionModeFinished(mode)
        window.statusBarColor = 0
    }

    private fun finishActionMode() {
        actionMode?.finish()
        actionMode = null
    }

    override fun dueDateChanged() {
        taskEditFragment!!.onDueDateChanged()
    }

    override fun onListChanged(filter: Filter?) {
        taskEditFragment!!.onRemoteListChanged(filter)
    }

    init {
        lifecycleScope.launchWhenResumed {
            playServices.check(this@MainActivity)
        }
    }

    companion object {
        /** For indicating the new list screen should be launched at fragment setup time  */
        const val TOKEN_CREATE_NEW_LIST_NAME = "newListName" // $NON-NLS-1$
        const val OPEN_FILTER = "open_filter" // $NON-NLS-1$
        const val LOAD_FILTER = "load_filter"
        const val CREATE_TASK = "open_task" // $NON-NLS-1$
        const val OPEN_TASK = "open_new_task" // $NON-NLS-1$
        const val REMOVE_TASK = "remove_task"
        const val FINISH_AFFINITY = "finish_affinity"
        private const val FRAG_TAG_TASK_LIST = "frag_tag_task_list"
        private const val FRAG_TAG_WHATS_NEW = "frag_tag_whats_new"
        private const val EXTRA_FILTER = "extra_filter"
        private const val FLAG_FROM_HISTORY
                = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY

        val Intent.getFilter: Filter?
            get() = if (isFromHistory) {
                null
            } else {
                getParcelableExtra<Filter?>(OPEN_FILTER)?.let {
                    removeExtra(OPEN_FILTER)
                    it
                }
            }

        val Intent.getFilterString: String?
            get() = if (isFromHistory) {
                null
            } else {
                getStringExtra(LOAD_FILTER)?.let {
                    removeExtra(LOAD_FILTER)
                    it
                }
            }

        val Intent.removeTask: Boolean
            get() = if (isFromHistory) {
                false
            } else {
                getBooleanExtra(REMOVE_TASK, false).let {
                    removeExtra(REMOVE_TASK)
                    it
                }
            }

        val Intent.finishAffinity: Boolean
            get() = if (isFromHistory) {
                false
            } else {
                getBooleanExtra(FINISH_AFFINITY, false).let {
                    removeExtra(FINISH_AFFINITY)
                    it
                }
            }

        val Intent.isFromHistory: Boolean
            get() = flags and FLAG_FROM_HISTORY == FLAG_FROM_HISTORY

        val Intent.broughtToFront: Boolean
            get() = flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT > 0

        val Intent.flagsToString
            get() = if (BuildConfig.DEBUG) "" else
                Intent::class.java.declaredFields
                        .filter { it.name.startsWith("FLAG_") }
                        .filter { flags or it.getInt(null) == flags }
                        .joinToString(" | ") { it.name }
    }
}