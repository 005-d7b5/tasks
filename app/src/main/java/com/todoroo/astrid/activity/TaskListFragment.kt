/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.activity

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.speech.RecognizerIntent
import android.view.*
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedList
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnClick
import com.google.android.material.snackbar.Snackbar
import com.google.ical.values.RRule
import com.todoroo.andlib.utility.AndroidUtilities
import com.todoroo.andlib.utility.DateUtilities
import com.todoroo.astrid.adapter.TaskAdapter
import com.todoroo.astrid.adapter.TaskAdapterProvider
import com.todoroo.astrid.api.*
import com.todoroo.astrid.core.BuiltInFilterExposer
import com.todoroo.astrid.dao.TaskDao
import com.todoroo.astrid.data.Task
import com.todoroo.astrid.service.*
import com.todoroo.astrid.timers.TimerPlugin
import com.todoroo.astrid.utility.Flags
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tasks.LocalBroadcastManager
import org.tasks.R
import org.tasks.ShortcutManager
import org.tasks.activities.*
import org.tasks.activities.ListPicker.Companion.newListPicker
import org.tasks.analytics.Firebase
import org.tasks.caldav.BaseCaldavCalendarSettingsActivity
import org.tasks.data.CaldavDao
import org.tasks.data.TagDataDao
import org.tasks.data.TaskContainer
import org.tasks.db.SuspendDbUtils.chunkedMap
import org.tasks.dialogs.DateTimePicker.Companion.newDateTimePicker
import org.tasks.dialogs.DialogBuilder
import org.tasks.dialogs.SortDialog
import org.tasks.filters.PlaceFilter
import org.tasks.intents.TaskIntents
import org.tasks.notifications.NotificationManager
import org.tasks.preferences.Device
import org.tasks.preferences.Preferences
import org.tasks.sync.SyncAdapters
import org.tasks.tags.TagPickerActivity
import org.tasks.tasklist.*
import org.tasks.themes.ColorProvider
import org.tasks.themes.ThemeColor
import org.tasks.ui.TaskListViewModel
import org.tasks.ui.Toaster
import timber.log.Timber
import java.text.ParseException
import java.time.format.FormatStyle
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class TaskListFragment : Fragment(), OnRefreshListener, Toolbar.OnMenuItemClickListener,
        MenuItem.OnActionExpandListener, SearchView.OnQueryTextListener, ActionMode.Callback,
        TaskViewHolder.ViewHolderCallbacks {
    private val refreshReceiver = RefreshReceiver()
    private val repeatConfirmationReceiver = RepeatConfirmationReceiver()

    @Inject lateinit var syncAdapters: SyncAdapters
    @Inject lateinit var taskDeleter: TaskDeleter
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dialogBuilder: DialogBuilder
    @Inject lateinit var taskCreator: TaskCreator
    @Inject lateinit var timerPlugin: TimerPlugin
    @Inject lateinit var viewHolderFactory: ViewHolderFactory
    @Inject lateinit var localBroadcastManager: LocalBroadcastManager
    @Inject lateinit var device: Device
    @Inject lateinit var taskMover: TaskMover
    @Inject lateinit var toaster: Toaster
    @Inject lateinit var taskAdapterProvider: TaskAdapterProvider
    @Inject lateinit var taskDao: TaskDao
    @Inject lateinit var taskDuplicator: TaskDuplicator
    @Inject lateinit var tagDataDao: TagDataDao
    @Inject lateinit var caldavDao: CaldavDao
    @Inject lateinit var defaultThemeColor: ThemeColor
    @Inject lateinit var colorProvider: ColorProvider
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var shortcutManager: ShortcutManager
    @Inject lateinit var taskCompleter: TaskCompleter
    @Inject lateinit var locale: Locale
    @Inject lateinit var firebase: Firebase
    
    @BindView(R.id.swipe_layout)
    lateinit var swipeRefreshLayout: SwipeRefreshLayout

    @BindView(R.id.swipe_layout_empty)
    lateinit var emptyRefreshLayout: SwipeRefreshLayout

    @BindView(R.id.toolbar)
    lateinit var toolbar: Toolbar

    @BindView(R.id.task_list_coordinator)
    lateinit var coordinatorLayout: CoordinatorLayout

    @BindView(R.id.recycler_view)
    lateinit var recyclerView: RecyclerView
    
    private val listViewModel: TaskListViewModel by viewModels()
    private lateinit var taskAdapter: TaskAdapter
    private var recyclerAdapter: TaskListRecyclerAdapter? = null
    private lateinit var filter: Filter
    private val searchSubject = PublishSubject.create<String>()
    private var searchDisposable: Disposable? = null
    private lateinit var search: MenuItem
    private var searchQuery: String? = null
    private var mode: ActionMode? = null
    private lateinit var themeColor: ThemeColor
    private lateinit var callbacks: TaskListFragmentCallbackHandler

    override fun onRefresh() {
        lifecycleScope.launch {
            if (syncAdapters.sync(true)) {
                delay(1000)
                setSyncOngoing()
            } else {
                refresh()
            }
        }
    }

    private fun setSyncOngoing() {
        AndroidUtilities.assertMainThread()
        val ongoing = preferences.isSyncOngoing
        swipeRefreshLayout.isRefreshing = ongoing
        emptyRefreshLayout.isRefreshing = ongoing
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null) {
            val longArray = savedInstanceState.getLongArray(EXTRA_SELECTED_TASK_IDS)
            if (longArray?.isNotEmpty() == true) {
                taskAdapter.setSelected(longArray.toList())
                startActionMode()
            }
        }
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        callbacks = activity as TaskListFragmentCallbackHandler
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val selectedTaskIds: List<Long> = taskAdapter.getSelected()
        outState.putLongArray(EXTRA_SELECTED_TASK_IDS, selectedTaskIds.toLongArray())
        outState.putString(EXTRA_SEARCH, searchQuery)
        outState.putLongArray(EXTRA_COLLAPSED, taskAdapter.getCollapsed().toLongArray())
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val parent = inflater.inflate(R.layout.fragment_task_list, container, false)
        ButterKnife.bind(this, parent)
        filter = getFilter()
        themeColor = if (filter.tint != 0) colorProvider.getThemeColor(filter.tint, true) else defaultThemeColor
        filter.setFilterQueryOverride(null)

        // set up list adapters
        taskAdapter = taskAdapterProvider.createTaskAdapter(filter)
        taskAdapter.setCollapsed(savedInstanceState?.getLongArray(EXTRA_COLLAPSED))
        if (savedInstanceState != null) {
            searchQuery = savedInstanceState.getString(EXTRA_SEARCH)
        }
        listViewModel.setFilter((if (searchQuery == null) filter else createSearchFilter(searchQuery!!)))
        (recyclerView.itemAnimator as DefaultItemAnimator).supportsChangeAnimations = false
        recyclerView.layoutManager = LinearLayoutManager(context)
        listViewModel.observe(this) {
            lifecycleScope.launch {
                submitList(it)
                if (it.isEmpty()) {
                    swipeRefreshLayout.visibility = View.GONE
                    emptyRefreshLayout.visibility = View.VISIBLE
                } else {
                    swipeRefreshLayout.visibility = View.VISIBLE
                    emptyRefreshLayout.visibility = View.GONE
                }
            }
        }
        setupRefresh(swipeRefreshLayout)
        setupRefresh(emptyRefreshLayout)
        toolbar.title = filter.listingTitle
        toolbar.setNavigationIcon(R.drawable.ic_outline_menu_24px)
        toolbar.setNavigationOnClickListener { callbacks.onNavigationIconClicked() }
        toolbar.setOnMenuItemClickListener(this)
        setupMenu()
        return parent
    }

    private suspend fun submitList(tasks: List<TaskContainer>) {
        if (tasks is PagedList<*>) {
            if (recyclerAdapter !is PagedListRecyclerAdapter) {
                setAdapter(
                        PagedListRecyclerAdapter(
                                taskAdapter, recyclerView, viewHolderFactory, this, tasks, preferences))
                return
            }
        } else if (recyclerAdapter !is DragAndDropRecyclerAdapter) {
            setAdapter(
                    DragAndDropRecyclerAdapter(
                            lifecycleScope, taskAdapter, recyclerView, viewHolderFactory, this, tasks, preferences))
            return
        }
        recyclerAdapter?.submitList(tasks)
    }

    private fun setAdapter(adapter: TaskListRecyclerAdapter) {
        recyclerAdapter = adapter
        recyclerView.adapter = adapter
        taskAdapter.setDataSource(adapter)
    }

    private fun setupMenu() {
        val menu = toolbar.menu
        menu.clear()
        if (filter.hasBeginningMenu()) {
            toolbar.inflateMenu(filter.beginningMenu)
        }
        toolbar.inflateMenu(R.menu.menu_task_list_fragment)
        if (filter.hasMenu()) {
            toolbar.inflateMenu(filter.menu)
        }
        val hidden = menu.findItem(R.id.menu_show_hidden)
        val completed = menu.findItem(R.id.menu_show_completed)
        if (!taskAdapter.supportsHiddenTasks() || !filter.supportsHiddenTasks()) {
            completed.isChecked = true
            completed.isEnabled = false
            hidden.isChecked = true
            hidden.isEnabled = false
        } else {
            hidden.isChecked = preferences.getBoolean(R.string.p_show_hidden_tasks, false)
            completed.isChecked = preferences.getBoolean(R.string.p_show_completed_tasks, false)
        }
        val sortMenu = menu.findItem(R.id.menu_sort)
        if (!filter.supportsSorting()) {
            sortMenu.isEnabled = false
            sortMenu.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        if (preferences.usePagedQueries()
                || !filter.supportsSubtasks()
                || taskAdapter.supportsAstridSorting()) {
            menu.findItem(R.id.menu_collapse_subtasks).isVisible = false
            menu.findItem(R.id.menu_expand_subtasks).isVisible = false
        }
        menu.findItem(R.id.menu_voice_add).isVisible = device.voiceInputAvailable()
        search = menu.findItem(R.id.menu_search).setOnActionExpandListener(this)
        (search.actionView as SearchView).setOnQueryTextListener(this)
        themeColor.apply(toolbar)
    }

    private fun openFilter(filter: Filter?) {
        if (filter == null) {
            startActivity(TaskIntents.getTaskListByIdIntent(context, null))
        } else {
            startActivity(TaskIntents.getTaskListIntent(context, filter))
        }
    }

    private fun searchByQuery(query: String?) {
        searchQuery = query?.trim { it <= ' ' } ?: ""
        if (searchQuery?.isEmpty() == true) {
            listViewModel.searchByFilter(
                    BuiltInFilterExposer.getMyTasksFilter(requireContext().resources))
        } else {
            val savedFilter = createSearchFilter(searchQuery!!)
            listViewModel.searchByFilter(savedFilter)
        }
    }

    private fun createSearchFilter(query: String): Filter {
        return SearchFilter(getString(R.string.FLA_search_filter, query), query)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_voice_add -> {
                val recognition = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                recognition.putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                recognition.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                recognition.putExtra(
                        RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_create_prompt))
                startActivityForResult(recognition, VOICE_RECOGNITION_REQUEST_CODE)
                true
            }
            R.id.menu_sort -> {
                SortDialog.newSortDialog(filter)
                        .show(childFragmentManager, FRAG_TAG_SORT_DIALOG)
                true
            }
            R.id.menu_show_hidden -> {
                item.isChecked = !item.isChecked
                preferences.setBoolean(R.string.p_show_hidden_tasks, item.isChecked)
                loadTaskListContent()
                localBroadcastManager.broadcastRefresh()
                true
            }
            R.id.menu_show_completed -> {
                item.isChecked = !item.isChecked
                preferences.setBoolean(R.string.p_show_completed_tasks, item.isChecked)
                loadTaskListContent()
                localBroadcastManager.broadcastRefresh()
                true
            }
            R.id.menu_clear_completed -> {
                dialogBuilder
                        .newDialog(R.string.clear_completed_tasks_confirmation)
                        .setPositiveButton(android.R.string.ok) { _, _ -> clearCompleted() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                true
            }
            R.id.menu_filter_settings -> {
                val filterSettings = Intent(activity, FilterSettingsActivity::class.java)
                filterSettings.putExtra(FilterSettingsActivity.TOKEN_FILTER, filter)
                startActivityForResult(filterSettings, REQUEST_LIST_SETTINGS)
                true
            }
            R.id.menu_caldav_list_fragment -> {
                val calendar = (filter as CaldavFilter).calendar
                lifecycleScope.launch {
                    val account = caldavDao.getAccountByUuid(calendar.account!!)
                    val caldavSettings = Intent(activity, account!!.listSettingsClass())
                    caldavSettings.putExtra(BaseCaldavCalendarSettingsActivity.EXTRA_CALDAV_CALENDAR, calendar)
                    startActivityForResult(caldavSettings, REQUEST_LIST_SETTINGS)
                }
                true
            }
            R.id.menu_location_settings -> {
                val place = (filter as PlaceFilter).place
                val intent = Intent(activity, PlaceSettingsActivity::class.java)
                intent.putExtra(PlaceSettingsActivity.EXTRA_PLACE, place as Parcelable)
                startActivityForResult(intent, REQUEST_LIST_SETTINGS)
                true
            }
            R.id.menu_gtasks_list_settings -> {
                val gtasksSettings = Intent(activity, GoogleTaskListSettingsActivity::class.java)
                gtasksSettings.putExtra(
                        GoogleTaskListSettingsActivity.EXTRA_STORE_DATA, (filter as GtasksFilter).list)
                startActivityForResult(gtasksSettings, REQUEST_LIST_SETTINGS)
                true
            }
            R.id.menu_tag_settings -> {
                val tagSettings = Intent(activity, TagSettingsActivity::class.java)
                tagSettings.putExtra(TagSettingsActivity.EXTRA_TAG_DATA, (filter as TagFilter).tagData)
                startActivityForResult(tagSettings, REQUEST_LIST_SETTINGS)
                true
            }
            R.id.menu_expand_subtasks -> {
                lifecycleScope.launch {
                    taskDao.setCollapsed(preferences, filter, false)
                    localBroadcastManager.broadcastRefresh()
                }
                true
            }
            R.id.menu_collapse_subtasks -> {
                lifecycleScope.launch {
                    taskDao.setCollapsed(preferences, filter, true)
                    localBroadcastManager.broadcastRefresh()
                }
                true
            }
            R.id.menu_open_map -> {
                (filter as PlaceFilter).openMap(context)
                true
            }
            R.id.menu_share -> {
                lifecycleScope.launch {
                    send(taskDao.fetchTasks(preferences, filter))
                }
                true
            }
            else -> onOptionsItemSelected(item)
        }
    }

    private fun clearCompleted() = lifecycleScope.launch {
        val count = taskDeleter.clearCompleted(filter)
        toaster.longToast(R.string.delete_multiple_tasks_confirmation, count)
    }

    @OnClick(R.id.fab)
    fun createNewTask() {
        lifecycleScope.launch {
            shortcutManager.reportShortcutUsed(ShortcutManager.SHORTCUT_NEW_TASK)
            onTaskListItemClicked(addTask(""))
        }
    }

    private suspend fun addTask(title: String): Task {
        return taskCreator.createWithValues(filter, title)
    }

    private fun setupRefresh(layout: SwipeRefreshLayout) {
        layout.setOnRefreshListener(this)
        layout.setColorSchemeColors(
                colorProvider.getPriorityColor(0, true),
                colorProvider.getPriorityColor(1, true),
                colorProvider.getPriorityColor(2, true),
                colorProvider.getPriorityColor(3, true))
    }

    override fun onResume() {
        super.onResume()
        localBroadcastManager.registerRefreshReceiver(refreshReceiver)
        localBroadcastManager.registerRepeatReceiver(repeatConfirmationReceiver)
        refresh()
    }

    fun makeSnackbar(@StringRes res: Int, vararg args: Any?): Snackbar {
        return makeSnackbar(getString(res, *args))
    }

    private fun makeSnackbar(text: String): Snackbar {
        val snackbar = Snackbar.make(coordinatorLayout, text, 8000)
                .setTextColor(requireActivity().getColor(R.color.snackbar_text_color))
                .setActionTextColor(requireActivity().getColor(R.color.snackbar_action_color))
        snackbar.view.setBackgroundColor(requireActivity().getColor(R.color.snackbar_background))
        return snackbar
    }

    override fun onPause() {
        super.onPause()
        localBroadcastManager.unregisterReceiver(repeatConfirmationReceiver)
        localBroadcastManager.unregisterReceiver(refreshReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (searchDisposable != null && !searchDisposable!!.isDisposed) {
            searchDisposable!!.dispose()
        }
    }

    fun collapseSearchView(): Boolean {
        return (search.isActionViewExpanded
                && (search.collapseActionView() || !search.isActionViewExpanded))
    }

    private fun refresh() {
        loadTaskListContent()
        setSyncOngoing()
    }

    fun loadTaskListContent() {
        listViewModel.invalidate()
    }

    fun getFilter(): Filter {
        return requireArguments().getParcelable(EXTRA_FILTER)!!
    }

    private fun onTaskCreated(tasks: List<Task>) {
        for (task in tasks) {
            onTaskCreated(task.uuid)
        }
        lifecycleScope.launch {
            syncAdapters.sync()
        }
        loadTaskListContent()
    }

    fun onTaskCreated(uuid: String) {
        lifecycleScope.launch {
            taskAdapter.onTaskCreated(uuid)
        }
    }

    private suspend fun onTaskDelete(task: Task) {
        (activity as MainActivity?)?.taskEditFragment?.let {
            if (task.id == it.editViewModel.task?.id) {
                it.editViewModel.discard()
            }
        }
        timerPlugin.stopTimer(task)
        taskAdapter.onTaskDeleted(task)
        loadTaskListContent()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            VOICE_RECOGNITION_REQUEST_CODE -> if (resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch {
                    val match: List<String>? = data!!.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    if (match != null && match.isNotEmpty() && match[0].isNotEmpty()) {
                        var recognizedSpeech = match[0]
                        recognizedSpeech = (recognizedSpeech.substring(0, 1).toUpperCase()
                                + recognizedSpeech.substring(1).toLowerCase())
                        onTaskListItemClicked(addTask(recognizedSpeech))
                    }
                }
            }
            REQUEST_MOVE_TASKS -> if (resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch {
                    taskMover.move(
                            taskAdapter.getSelected(),
                            data!!.getParcelableExtra(ListPicker.EXTRA_SELECTED_FILTER))
                }
                finishActionMode()
            }
            REQUEST_LIST_SETTINGS -> if (resultCode == Activity.RESULT_OK) {
                val action = data!!.action
                if (ACTION_DELETED == action) {
                    openFilter(null)
                } else if (ACTION_RELOAD == action) {
                    openFilter(data.getParcelableExtra(MainActivity.OPEN_FILTER))
                }
            }
            REQUEST_TAG_TASKS -> if (resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch {
                    val modified = tagDataDao.applyTags(
                            taskDao.fetch(
                                    data!!.getSerializableExtra(TagPickerActivity.EXTRA_TASKS) as ArrayList<Long>),
                            data.getParcelableArrayListExtra(TagPickerActivity.EXTRA_PARTIALLY_SELECTED)!!,
                            data.getParcelableArrayListExtra(TagPickerActivity.EXTRA_SELECTED)!!)
                    taskDao.touch(modified)
                }
                finishActionMode()
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        return onOptionsItemSelected(item)
    }

    fun onTaskListItemClicked(task: Task?) = lifecycleScope.launch {
        callbacks.onTaskListItemClicked(task)
    }

    override fun onMenuItemActionExpand(item: MenuItem): Boolean {
        searchDisposable = searchSubject
                .debounce(SEARCH_DEBOUNCE_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { query: String? -> searchByQuery(query) }
        if (searchQuery == null) {
            searchByQuery("")
        }
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            menu.getItem(i).isVisible = false
        }
        return true
    }

    override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
        listViewModel.searchByFilter(filter)
        searchDisposable?.dispose()
        searchQuery = null
        setupMenu()
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        openFilter(createSearchFilter(query.trim { it <= ' ' }))
        search.collapseActionView()
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        searchSubject.onNext(query)
        return true
    }

    fun broadcastRefresh() {
        localBroadcastManager.broadcastRefresh()
    }

    override fun onCreateActionMode(actionMode: ActionMode, menu: Menu): Boolean {
        val inflater = actionMode.menuInflater
        inflater.inflate(R.menu.menu_multi_select, menu)
        themeColor.colorMenu(menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val selected = taskAdapter.getSelected()
        return when (item.itemId) {
            R.id.edit_tags -> {
                lifecycleScope.launch {
                    val tags = tagDataDao.getTagSelections(selected)
                    val intent = Intent(context, TagPickerActivity::class.java)
                    intent.putExtra(TagPickerActivity.EXTRA_TASKS, selected)
                    intent.putParcelableArrayListExtra(
                            TagPickerActivity.EXTRA_PARTIALLY_SELECTED,
                            ArrayList(tagDataDao.getByUuid(tags.first!!)))
                    intent.putParcelableArrayListExtra(
                            TagPickerActivity.EXTRA_SELECTED, ArrayList(tagDataDao.getByUuid(tags.second!!)))
                    startActivityForResult(intent, REQUEST_TAG_TASKS)
                }
                true
            }
            R.id.move_tasks -> {
                lifecycleScope.launch {
                    val singleFilter = taskMover.getSingleFilter(selected)
                    val fragment = if (singleFilter == null) {
                        newListPicker(this@TaskListFragment, REQUEST_MOVE_TASKS)
                    } else {
                        newListPicker(singleFilter, this@TaskListFragment, REQUEST_MOVE_TASKS)
                    }
                    fragment.show(parentFragmentManager, FRAG_TAG_REMOTE_LIST_PICKER)
                }
                true
            }
            R.id.menu_select_all -> {
                lifecycleScope.launch {
                    taskAdapter.setSelected(taskDao.fetchTasks(preferences, filter)
                            .map(TaskContainer::getId))
                    updateModeTitle()
                    recyclerAdapter?.notifyDataSetChanged()
                }
                true
            }
            R.id.menu_share -> {
                lifecycleScope.launch {
                    selected.chunkedMap { taskDao.fetchTasks(preferences, IdListFilter(it)) }
                            .apply { send(this) }
                }
                true
            }
            R.id.delete -> {
                dialogBuilder
                        .newDialog(R.string.delete_selected_tasks)
                        .setPositiveButton(
                                android.R.string.ok) { _, _ -> deleteSelectedItems(selected) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                true
            }
            R.id.copy_tasks -> {
                dialogBuilder
                        .newDialog(R.string.copy_selected_tasks)
                        .setPositiveButton(
                                android.R.string.ok) { _, _ -> copySelectedItems(selected) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                true
            }
            else -> false
        }
    }

    private fun send(tasks: List<TaskContainer>) {
        val intent = Intent(Intent.ACTION_SEND)
        val output = tasks.joinToString("\n") { t -> Task
            "${(if (t.isCompleted) "☑" else "☐").padStart(1 + t.getIndent() * 3, ' ')} ${t.title}"
        }
        intent.putExtra(Intent.EXTRA_SUBJECT, filter.listingTitle)
        intent.putExtra(Intent.EXTRA_TEXT, output)
        intent.type = "text/plain"
        startActivity(Intent.createChooser(intent, null))
        finishActionMode()
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        this.mode = null
        if (taskAdapter.numSelected > 0) {
            taskAdapter.clearSelections()
            recyclerAdapter?.notifyDataSetChanged()
        }
    }

    fun showDateTimePicker(task: TaskContainer) {
        val fragmentManager = parentFragmentManager
        if (fragmentManager.findFragmentByTag(FRAG_TAG_DATE_TIME_PICKER) == null) {
            newDateTimePicker(
                    task.id,
                    task.dueDate,
                    preferences.getBoolean(R.string.p_auto_dismiss_datetime_list_screen, false))
                    .show(fragmentManager, FRAG_TAG_DATE_TIME_PICKER)
        }
    }

    interface TaskListFragmentCallbackHandler {
        suspend fun onTaskListItemClicked(task: Task?)
        fun onNavigationIconClicked()
    }

    val isActionModeActive: Boolean
        get() = mode != null

    fun startActionMode() {
        if (mode == null) {
            mode = (activity as AppCompatActivity).startSupportActionMode(this)
            updateModeTitle()
            Flags.set(Flags.TLFP_NO_INTERCEPT_TOUCH)
        }
    }

    fun finishActionMode() {
        mode?.finish()
    }

    fun updateModeTitle() {
        if (mode != null) {
            val count = max(1, taskAdapter.numSelected)
            mode!!.title = count.toString()
        }
    }

    private fun deleteSelectedItems(tasks: List<Long>) = lifecycleScope.launch {
        finishActionMode()

        val result = withContext(NonCancellable) {
            taskDeleter.markDeleted(tasks)
        }
        result.forEach { onTaskDelete(it) }
        makeSnackbar(R.string.delete_multiple_tasks_confirmation, result.size.toString()).show()
    }

    private fun copySelectedItems(tasks: List<Long>) = lifecycleScope.launch {
        finishActionMode()
        val duplicates = withContext(NonCancellable) {
            taskDuplicator.duplicate(tasks)
        }
        onTaskCreated(duplicates)
        makeSnackbar(R.string.copy_multiple_tasks_confirmation, duplicates.size.toString()).show()
    }

    fun clearCollapsed() = taskAdapter.clearCollapsed()

    override fun onCompletedTask(task: TaskContainer, newState: Boolean) {
        lifecycleScope.launch {
            taskCompleter.setComplete(task.getTask(), newState)
            taskAdapter.onCompletedTask(task, newState)
            loadTaskListContent()
        }
    }

    override fun onClick(taskViewHolder: TaskViewHolder) {
        if (isActionModeActive) {
            recyclerAdapter?.toggle(taskViewHolder)
        } else {
            onTaskListItemClicked(taskViewHolder.task.getTask())
        }
    }

    override fun onClick(filter: Filter) {
        if (!isActionModeActive) {
            val context = activity
            context?.startActivity(TaskIntents.getTaskListIntent(context, filter))
        }
    }

    override fun onLongPress(taskViewHolder: TaskViewHolder): Boolean {
        if (recyclerAdapter?.dragAndDropEnabled() != true) {
            startActionMode()
        }
        if (isActionModeActive && !taskViewHolder.moving) {
            recyclerAdapter?.toggle(taskViewHolder)
        }
        return true
    }

    override fun onChangeDueDate(task: TaskContainer) {
        showDateTimePicker(task)
    }

    override fun toggleSubtasks(task: TaskContainer, collapsed: Boolean) {
        lifecycleScope.launch {
            taskDao.setCollapsed(task.id, collapsed)
            broadcastRefresh()
        }
    }

    private inner class RefreshReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }

    private inner class RepeatConfirmationReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val taskId = intent.getLongExtra(AstridApiConstants.EXTRAS_TASK_ID, 0)
            if (taskId > 0) {
                val oldDueDate = intent.getLongExtra(AstridApiConstants.EXTRAS_OLD_DUE_DATE, 0)
                val newDueDate = intent.getLongExtra(AstridApiConstants.EXTRAS_NEW_DUE_DATE, 0)
                lifecycleScope.launch {
                    val task = taskDao.fetch(taskId)
                    try {
                        val dueDateString = DateUtilities.getRelativeDateTime(
                                context, newDueDate, locale, FormatStyle.LONG, true)
                        makeSnackbar(R.string.repeat_snackbar, task!!.title, dueDateString)
                                .setAction(R.string.DLG_undo) {
                                    task.setDueDateAdjustingHideUntil(oldDueDate)
                                    task.completionDate = 0L
                                    try {
                                        val rrule = RRule(task.getRecurrenceWithoutFrom())
                                        val count = rrule.count
                                        if (count > 0) {
                                            rrule.count = count + 1
                                        }
                                        task.setRecurrence(rrule, task.repeatAfterCompletion())
                                    } catch (e: ParseException) {
                                        Timber.e(e)
                                    }
                                    lifecycleScope.launch(NonCancellable) {
                                        taskDao.save(task)
                                    }
                                }
                                .show()
                    } catch (e: Exception) {
                        firebase.reportException(e)
                    }
                }
            }
        }
    }

    companion object {
        const val TAGS_METADATA_JOIN = "for_tags" // $NON-NLS-1$
        const val GTASK_METADATA_JOIN = "googletask" // $NON-NLS-1$
        const val CALDAV_METADATA_JOIN = "for_caldav" // $NON-NLS-1$
        const val ACTION_RELOAD = "action_reload"
        const val ACTION_DELETED = "action_deleted"
        private const val EXTRA_SELECTED_TASK_IDS = "extra_selected_task_ids"
        private const val EXTRA_SEARCH = "extra_search"
        private const val EXTRA_COLLAPSED = "extra_collapsed"
        private const val VOICE_RECOGNITION_REQUEST_CODE = 1234
        private const val EXTRA_FILTER = "extra_filter"
        private const val FRAG_TAG_REMOTE_LIST_PICKER = "frag_tag_remote_list_picker"
        private const val FRAG_TAG_SORT_DIALOG = "frag_tag_sort_dialog"
        private const val FRAG_TAG_DATE_TIME_PICKER = "frag_tag_date_time_picker"
        private const val REQUEST_LIST_SETTINGS = 10101
        private const val REQUEST_MOVE_TASKS = 10103
        private const val REQUEST_TAG_TASKS = 10106
        private const val SEARCH_DEBOUNCE_TIMEOUT = 300
        fun newTaskListFragment(context: Context, filter: Filter?): TaskListFragment {
            val fragment = TaskListFragment()
            val bundle = Bundle()
            bundle.putParcelable(
                    EXTRA_FILTER,
                    filter ?: BuiltInFilterExposer.getMyTasksFilter(context.resources))
            fragment.arguments = bundle
            return fragment
        }
    }
}