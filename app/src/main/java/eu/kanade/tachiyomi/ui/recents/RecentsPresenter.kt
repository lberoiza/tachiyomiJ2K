package eu.kanade.tachiyomi.ui.recents

import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.HistoryImpl
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.download.DownloadServiceListener
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.download.model.DownloadQueue
import eu.kanade.tachiyomi.data.library.LibraryServiceListener
import eu.kanade.tachiyomi.data.library.LibraryUpdateService
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.chapter.ChapterFilter
import eu.kanade.tachiyomi.util.chapter.ChapterFilter.Companion.filterChaptersByScanlators
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.system.executeOnIO
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class RecentsPresenter(
    val preferences: PreferencesHelper = Injekt.get(),
    val downloadManager: DownloadManager = Injekt.get(),
    val db: DatabaseHelper = Injekt.get(),
    private val chapterFilter: ChapterFilter = Injekt.get(),
) : BaseCoroutinePresenter<RecentsController>(), DownloadQueue.DownloadListener, LibraryServiceListener, DownloadServiceListener {

    private var recentsJob: Job? = null
    var recentItems = listOf<RecentMangaItem>()
        private set
    var query = ""
        set(value) {
            field = value
            resetOffsets()
        }
    private val newAdditionsHeader = RecentMangaHeaderItem(RecentMangaHeaderItem.NEWLY_ADDED)
    private val newChaptersHeader = RecentMangaHeaderItem(RecentMangaHeaderItem.NEW_CHAPTERS)
    private val continueReadingHeader =
        RecentMangaHeaderItem(RecentMangaHeaderItem.CONTINUE_READING)
    var finished = false
    private var shouldMoveToTop = false
    var viewType: Int = preferences.recentsViewType().get()
        private set
    val expandedSectionsMap = mutableMapOf<String, Boolean>()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun resetOffsets() {
        finished = false
        shouldMoveToTop = true
        pageOffset = 0
        expandedSectionsMap.clear()
    }

    private var pageOffset = 0
    var isLoading = false
        private set

    private val isOnFirstPage: Boolean
        get() = pageOffset == 0

    override fun onCreate() {
        super.onCreate()
        downloadManager.addListener(this)
        DownloadService.addListener(this)
        LibraryUpdateService.setListener(this)
        if (lastRecents != null) {
            if (recentItems.isEmpty()) {
                recentItems = lastRecents ?: emptyList()
            }
            lastRecents = null
        }
        getRecents()
        listOf(
            preferences.groupChaptersHistory(),
            preferences.showReadInAllRecents(),
            preferences.sortFetchedTime(),
        ).forEach {
            it.asFlow()
                .drop(1)
                .onEach {
                    resetOffsets()
                    getRecents()
                }
                .launchIn(presenterScope)
        }
    }

    fun getRecents(updatePageCount: Boolean = false) {
        val oldQuery = query
        recentsJob?.cancel()
        recentsJob = presenterScope.launch {
            runRecents(oldQuery, updatePageCount)
        }
    }

    /**
     * Gets a set of recent entries based on preferred view type, unless changed by [customViewType]
     *
     * @param oldQuery used to determine while running this method the query has changed, and to cancel this
     * @param updatePageCount make true when fetching for more pages in the pagination scroll, otherwise make false to restart the list
     * @param retryCount used to not burden the db with infinite calls, should not be set as its a recursive param
     * @param itemCount also used in recursion to know how many items have been collected so far
     * @param limit used by the companion method to not recursively call this method, since the first batch is good enough
     * @param customViewType used to decide to use another view type instead of the one saved by preferences
     * @param includeReadAnyway also used by companion method to include the read manga, by default only unread manga is used
     */
    private suspend fun runRecents(
        oldQuery: String = "",
        updatePageCount: Boolean = false,
        retryCount: Int = 0,
        itemCount: Int = 0,
        limit: Int = -1,
        customViewType: Int? = null,
        includeReadAnyway: Boolean = false,
    ) {
        if (retryCount > 5) {
            finished = true
            setDownloadedChapters(recentItems)
            if (customViewType == null) {
                withContext(Dispatchers.Main) {
                    controller?.showLists(recentItems, false)
                    isLoading = false
                }
            }
            return
        }
        val viewType = customViewType ?: viewType

        val showRead = ((preferences.showReadInAllRecents().get() || query.isNotEmpty()) && limit != 0) || includeReadAnyway
        val isUngrouped = viewType > VIEW_TYPE_GROUP_ALL || query.isNotEmpty()
        val groupChaptersUpdates = preferences.collapseGroupedUpdates().get()
        val groupChaptersHistory = preferences.groupChaptersHistory().get()

        val isCustom = customViewType != null
        val isEndless = isUngrouped && limit != 0
        var extraCount = 0
        val cReading = when {
            viewType <= VIEW_TYPE_UNGROUP_ALL -> {
                db.getAllRecentsTypes(
                    query,
                    showRead,
                    isEndless,
                    if (isCustom) ENDLESS_LIMIT else pageOffset,
                    !updatePageCount && !isOnFirstPage,
                ).executeOnIO()
            }
            viewType == VIEW_TYPE_ONLY_HISTORY -> {
                val items = db.getHistoryUngrouped(
                    query,
                    if (isCustom) ENDLESS_LIMIT else pageOffset,
                    !updatePageCount && !isOnFirstPage,
                )
                if (groupChaptersHistory) {
                    items.executeOnIO().groupBy {
                        val date = it.history.last_read
                        it.manga.id to if (date <= 0L) "-1" else dateFormat.format(Date(date))
                    }
                        .mapNotNull { (key, mchs) ->
                            val manga = mchs.first().manga
                            val chapters = mchs.map { mch ->
                                mch.chapter.also { it.history = mch.history }
                            }.filterChaptersByScanlators(manga)
                            extraCount += mchs.size - chapters.size
                            if (chapters.isEmpty()) return@mapNotNull null
                            val existingItem = recentItems.find {
                                val date = Date(it.mch.history.last_read)
                                key == it.manga_id to dateFormat.format(date)
                            }?.takeIf { updatePageCount }
                            val sort = Comparator<Chapter> { c1, c2 ->
                                c2.dateRead!!.compareTo(c1.dateRead!!)
                            }
                            val (sortedChapters, firstChapter, subCount) =
                                setupExtraChapters(existingItem, chapters, sort)
                            extraCount += subCount
                            if (firstChapter == null) return@mapNotNull null
                            mchs.find { firstChapter.id == it.chapter.id }?.also {
                                it.extraChapters = sortedChapters
                            }
                        }
                } else {
                    items.executeOnIO()
                }
            }
            viewType == VIEW_TYPE_ONLY_UPDATES -> {
                db.getRecentChapters(
                    query,
                    if (isCustom) ENDLESS_LIMIT else pageOffset,
                    !updatePageCount && !isOnFirstPage,
                ).executeOnIO().groupBy {
                    val date = it.chapter.date_fetch
                    it.manga.id to if (date <= 0L) "-1" else dateFormat.format(Date(date))
                }
                    .mapNotNull { (key, mcs) ->
                        val manga = mcs.first().manga
                        val chapters = mcs.map { it.chapter }.filterChaptersByScanlators(manga)
                        extraCount += mcs.size - chapters.size
                        if (chapters.isEmpty()) return@mapNotNull null
                        val existingItem = recentItems.find {
                            val date = Date(it.chapter.date_fetch)
                            key == it.manga_id to dateFormat.format(date)
                        }?.takeIf { updatePageCount }
                        val sort: Comparator<Chapter> =
                            ChapterSort(manga, chapterFilter, preferences)
                                .sortComparator(true)
                        val (sortedChapters, firstChapter, subCount) =
                            setupExtraChapters(existingItem, chapters, sort)
                        extraCount += subCount
                        if (firstChapter == null) return@mapNotNull null
                        MangaChapterHistory(
                            manga,
                            firstChapter,
                            HistoryImpl().apply { last_read = firstChapter.date_fetch },
                            sortedChapters,
                        )
                    }
            }
            else -> emptyList()
        }

        if (cReading.size + cReading.sumOf { it.extraChapters.size } + extraCount < ENDLESS_LIMIT) {
            finished = true
        }

        if (!isCustom &&
            (pageOffset == 0 || updatePageCount)
        ) {
            pageOffset += cReading.size + cReading.sumOf { it.extraChapters.size } + extraCount
        }

        if (query != oldQuery) return
        val mangaList = cReading.distinctBy {
            if (query.isEmpty() && viewType != VIEW_TYPE_ONLY_HISTORY && viewType != VIEW_TYPE_ONLY_UPDATES) it.manga.id else it.chapter.id
        }.filter { mch ->
            if (updatePageCount && !isOnFirstPage && query.isEmpty()) {
                if (viewType != VIEW_TYPE_ONLY_HISTORY && viewType != VIEW_TYPE_ONLY_UPDATES) {
                    recentItems.none { mch.manga.id == it.mch.manga.id }
                } else {
                    recentItems.none { mch.chapter.id == it.mch.chapter.id }
                }
            } else {
                true
            }
        }
        val pairs = mangaList.mapNotNull {
            val chapter = run result@{
                when {
                    (viewType == VIEW_TYPE_ONLY_UPDATES && !groupChaptersUpdates) ||
                        (viewType == VIEW_TYPE_ONLY_HISTORY && !groupChaptersHistory) -> {
                        it.chapter
                    }
                    (it.chapter.read && viewType != VIEW_TYPE_ONLY_UPDATES) || it.chapter.id == null -> {
                        val nextChapter = getNextChapter(it.manga)
                            ?: if (showRead && it.chapter.id != null) it.chapter else null
                        if (viewType == VIEW_TYPE_ONLY_HISTORY && nextChapter != null) {
                            val unreadChapterIsAlreadyInList =
                                recentItems.any { item -> item.mch.manga.id == it.manga.id } ||
                                        mangaList.indexOfFirst { item ->
                                            item.manga.id == it.manga.id
                                        } > mangaList.indexOf(it)
                            if (unreadChapterIsAlreadyInList) {
                                return@result it.chapter
                            }
                        }
                        if (viewType == VIEW_TYPE_ONLY_HISTORY && nextChapter?.id != null &&
                            nextChapter.id != it.chapter.id
                        ) {
                            nextChapter.dateRead = it.chapter.dateRead
                            it.extraChapters = listOf(it.chapter) + it.extraChapters
                        }
                        nextChapter
                    }
                    it.history.id == null && viewType != VIEW_TYPE_ONLY_UPDATES -> {
                        getFirstUpdatedChapter(it.manga, it.chapter)
                            ?: if ((showRead && it.chapter.id != null)) it.chapter else null
                    }
                    else -> {
                        it.chapter
                    }
                }
            }
            if (chapter == null) if ((query.isNotEmpty() || viewType > VIEW_TYPE_UNGROUP_ALL) &&
                it.chapter.id != null
            ) {
                Pair(it, it.chapter)
            } else {
                null
            }
            else {
                Pair(it, chapter)
            }
        }
        val newItems = if (query.isEmpty() && !isUngrouped) {
            val nChaptersItems =
                pairs.asSequence()
                    .filter { it.first.history.id == null && it.first.chapter.id != null }
                    .sortedWith { f1, f2 ->
                        if (abs(f1.second.date_fetch - f2.second.date_fetch) <=
                            TimeUnit.HOURS.toMillis(12)
                        ) {
                            f2.second.date_upload.compareTo(f1.second.date_upload)
                        } else {
                            f2.second.date_fetch.compareTo(f1.second.date_fetch)
                        }
                    }
                    .take(4).map {
                        RecentMangaItem(
                            it.first,
                            it.second,
                            newChaptersHeader,
                        )
                    }.toMutableList()
            val cReadingItems =
                pairs.filter { it.first.history.id != null }.take(9 - nChaptersItems.size).map {
                    RecentMangaItem(
                        it.first,
                        it.second,
                        continueReadingHeader,
                    )
                }.toMutableList()
            if (nChaptersItems.isNotEmpty()) {
                nChaptersItems.add(RecentMangaItem(header = newChaptersHeader))
            }
            if (cReadingItems.isNotEmpty()) {
                cReadingItems.add(RecentMangaItem(header = continueReadingHeader))
            }
            val nAdditionsItems = pairs.filter { it.first.chapter.id == null }.take(4)
                .map { RecentMangaItem(it.first, it.second, newAdditionsHeader) }
            listOf(nChaptersItems, cReadingItems, nAdditionsItems).sortedByDescending {
                it.firstOrNull()?.mch?.history?.last_read ?: 0L
            }.flatten()
        } else {
            if (viewType == VIEW_TYPE_ONLY_UPDATES) {
                val map =
                    TreeMap<Date, MutableList<Pair<MangaChapterHistory, Chapter>>> { d1, d2 ->
                        d2
                            .compareTo(d1)
                    }
                val byDay =
                    pairs.groupByTo(map) { getMapKey(it.first.history.last_read) }
                byDay.flatMap {
                    val dateItem = DateItem(it.key, true)
                    it.value
                        .map { item -> RecentMangaItem(item.first, item.second, dateItem) }
                        .sortedByDescending { item ->
                            if (preferences.sortFetchedTime().get()) item.date_fetch else item.date_upload
                        }
                }
            } else {
                pairs.map { RecentMangaItem(it.first, it.second, null) }
            }
        }
        if (customViewType == null) {
            recentItems = if (isOnFirstPage || !updatePageCount) {
                newItems
            } else {
                recentItems + newItems
            }
        }
        val newCount = itemCount + newItems.size + newItems.sumOf { it.mch.extraChapters.size } + extraCount
        val hasNewItems = newItems.isNotEmpty()
        if (updatePageCount && (newCount < if (limit > 0) limit else 25) &&
            (viewType != VIEW_TYPE_GROUP_ALL || query.isNotEmpty()) &&
            limit != 0
        ) {
            runRecents(oldQuery, true, retryCount + (if (hasNewItems) 0 else 1), newCount)
            return
        }
        if (limit == -1) {
            setDownloadedChapters(recentItems)
            if (customViewType == null) {
                withContext(Dispatchers.Main) {
                    controller?.showLists(recentItems, hasNewItems, shouldMoveToTop)
                    isLoading = false
                    shouldMoveToTop = false
                }
            }
        }
    }

    private fun setupExtraChapters(
        existingItem: RecentMangaItem?,
        chapters: List<Chapter>,
        sort: Comparator<Chapter>,
    ): Triple<MutableList<Chapter>, Chapter?, Int> {
        var extraCount = 0
        val firstChapter: Chapter
        var sortedChapters: MutableList<Chapter>
        val reverseRead = viewType != VIEW_TYPE_ONLY_HISTORY
        if (existingItem != null) {
            extraCount += chapters.size
            val newChapters = existingItem.mch.extraChapters + chapters
            sortedChapters = newChapters.sortedWith(sort).toMutableList()
            sortedChapters = (
                sortedChapters.filter { !it.read } +
                    sortedChapters.filter { it.read }
                        .run { if (reverseRead) reversed() else this }
                ).toMutableList()
            existingItem.mch.extraChapters = sortedChapters
            return Triple(mutableListOf(), null, extraCount)
        }
        if (chapters.size == 1) {
            firstChapter = chapters.first()
            sortedChapters = mutableListOf()
        } else {
            sortedChapters = chapters.sortedWith(sort).toMutableList()
            firstChapter = sortedChapters.firstOrNull { !it.read }
                ?: sortedChapters.run { if (reverseRead) last() else first() }
            sortedChapters.last()
            sortedChapters.remove(firstChapter)
            sortedChapters = (
                sortedChapters.filter { !it.read } +
                    sortedChapters.filter { it.read }
                        .run { if (reverseRead) reversed() else this }
                ).toMutableList()
        }
        return Triple(sortedChapters, firstChapter, extraCount)
    }

    private fun getNextChapter(manga: Manga): Chapter? {
        val chapters = db.getChapters(manga).executeAsBlocking()
        return ChapterSort(manga, chapterFilter, preferences).getNextUnreadChapter(chapters, false)
    }

    private fun getFirstUpdatedChapter(manga: Manga, chapter: Chapter): Chapter? {
        val chapters = db.getChapters(manga).executeAsBlocking()
        return chapters.sortedWith(ChapterSort(manga, chapterFilter, preferences).sortComparator(true)).find {
            !it.read && abs(it.date_fetch - chapter.date_fetch) <= TimeUnit.HOURS.toMillis(12)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadManager.removeListener(this)
        LibraryUpdateService.removeListener(this)
        DownloadService.removeListener(this)
        lastRecents = recentItems
    }

    fun toggleGroupRecents(pref: Int, updatePref: Boolean = true) {
        if (updatePref) {
            preferences.recentsViewType().set(pref)
        }
        viewType = pref
        resetOffsets()
        getRecents()
    }

    /**
     * Finds and assigns the list of downloaded chapters.
     *
     * @param chapters the list of chapter from the database.
     */
    private fun setDownloadedChapters(chapters: List<RecentMangaItem>) {
        for (item in chapters.filter { it.chapter.id != null }) {
            if (downloadManager.isChapterDownloaded(item.chapter, item.mch.manga)) {
                item.status = Download.State.DOWNLOADED
            } else if (downloadManager.hasQueue()) {
                item.download = downloadManager.queue.find { it.chapter.id == item.chapter.id }
                item.status = item.download?.status ?: Download.State.default
            }

            item.downloadInfo = item.mch.extraChapters.map { chapter ->
                val downloadInfo = RecentMangaItem.DownloadInfo()
                downloadInfo.chapterId = chapter.id
                if (downloadManager.isChapterDownloaded(chapter, item.mch.manga)) {
                    downloadInfo.status = Download.State.DOWNLOADED
                } else if (downloadManager.hasQueue()) {
                    downloadInfo.download = downloadManager.queue.find { it.chapter.id == chapter.id }
                    downloadInfo.status = downloadInfo.download?.status ?: Download.State.default
                }
                downloadInfo
            }
        }
    }

    override fun updateDownload(download: Download) {
        recentItems.find {
            download.chapter.id == it.chapter.id ||
                download.chapter.id in it.mch.extraChapters.map { ch -> ch.id }
        }?.apply {
            if (chapter.id != download.chapter.id) {
                val downloadInfo = downloadInfo.find { it.chapterId == download.chapter.id }
                    ?: return@apply
                downloadInfo.download = download
            } else {
                this.download = download
            }
        }
        presenterScope.launchUI { controller?.updateChapterDownload(download) }
    }

    override fun updateDownloads() {
        presenterScope.launch {
            setDownloadedChapters(recentItems)
            withContext(Dispatchers.Main) {
                controller?.showLists(recentItems, true)
                controller?.updateDownloadStatus(!downloadManager.isPaused())
            }
        }
    }

    override fun downloadStatusChanged(downloading: Boolean) {
        presenterScope.launch {
            withContext(Dispatchers.Main) {
                controller?.updateDownloadStatus(downloading)
            }
        }
    }

    override fun onUpdateManga(manga: Manga?) {
        when {
            manga == null -> {
                presenterScope.launchUI { controller?.setRefreshing(false) }
            }
            manga.source == LibraryUpdateService.STARTING_UPDATE_SOURCE -> {
                presenterScope.launchUI { controller?.setRefreshing(true) }
            }
            else -> {
                getRecents()
            }
        }
    }

    /**
     * Deletes the given list of chapter.
     * @param chapter the chapter to delete.
     */
    fun deleteChapter(chapter: Chapter, manga: Manga, update: Boolean = true) {
        val source = Injekt.get<SourceManager>().getOrStub(manga.source)
        launchIO {
            downloadManager.deleteChapters(listOf(chapter), manga, source, true)
        }
        if (update) {
            val item = recentItems.find {
                chapter.id == it.chapter.id ||
                    chapter.id in it.mch.extraChapters.map { ch -> ch.id }
            } ?: return
            item.apply {
                if (chapter.id != item.chapter.id) {
                    val extraChapter = mch.extraChapters.find { it.id == chapter.id } ?: return@apply
                    val downloadInfo = downloadInfo.find { it.chapterId == chapter.id } ?: return@apply
                    if (extraChapter.bookmark && !preferences.removeBookmarkedChapters().get()) {
                        return@apply
                    }
                    downloadInfo.status = Download.State.NOT_DOWNLOADED
                    downloadInfo.download = null
                } else {
                    if (chapter.bookmark && !preferences.removeBookmarkedChapters().get()) {
                        return@apply
                    }
                    status = Download.State.NOT_DOWNLOADED
                    download = null
                }
            }

            controller?.showLists(recentItems, true)
        }
    }

    /**
     * Get date as time key
     *
     * @param date desired date
     * @return date as time key
     */
    private fun getMapKey(date: Long): Date {
        val cal = Calendar.getInstance()
        cal.time = Date(date)
        cal[Calendar.HOUR_OF_DAY] = 0
        cal[Calendar.MINUTE] = 0
        cal[Calendar.SECOND] = 0
        cal[Calendar.MILLISECOND] = 0
        return cal.time
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapter the chapter to download.
     */
    fun downloadChapter(manga: Manga, chapter: Chapter) {
        downloadManager.downloadChapters(manga, listOf(chapter))
    }

    fun startDownloadChapterNow(chapter: Chapter) {
        downloadManager.startDownloadNow(chapter)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChapterRead(
        chapter: Chapter,
        read: Boolean,
        lastRead: Int? = null,
        pagesLeft: Int? = null,
    ) {
        presenterScope.launch(Dispatchers.IO) {
            chapter.apply {
                this.read = read
                if (!read) {
                    last_page_read = lastRead ?: 0
                    pages_left = pagesLeft ?: 0
                }
            }
            db.updateChaptersProgress(listOf(chapter)).executeAsBlocking()
            getRecents()
        }
    }

    // History
    /**
     * Reset last read of chapter to 0L
     * @param history history belonging to chapter
     */
    fun removeFromHistory(history: History) {
        history.last_read = 0L
        history.time_read = 0L
        db.upsertHistoryLastRead(history).executeAsBlocking()
        getRecents()
    }

    /**
     * Removes all chapters belonging to manga from history.
     * @param mangaId id of manga
     */
    fun removeAllFromHistory(mangaId: Long) {
        val history = db.getHistoryByMangaId(mangaId).executeAsBlocking()
        history.forEach {
            it.last_read = 0L
            it.time_read = 0L
        }
        db.upsertHistoryLastRead(history).executeAsBlocking()
        getRecents()
    }

    fun requestNext() {
        if (!isLoading) {
            isLoading = true
            getRecents(true)
        }
    }

    fun deleteAllHistory() {
        presenterScope.launchIO {
            db.deleteHistory().executeAsBlocking()
            withUIContext {
                controller?.activity?.toast(R.string.clear_history_completed)
                getRecents()
            }
        }
    }

    companion object {
        private var lastRecents: List<RecentMangaItem>? = null

        fun onLowMemory() {
            lastRecents = null
        }

        const val VIEW_TYPE_GROUP_ALL = 0
        const val VIEW_TYPE_UNGROUP_ALL = 1
        const val VIEW_TYPE_ONLY_HISTORY = 2
        const val VIEW_TYPE_ONLY_UPDATES = 3
        const val ENDLESS_LIMIT = 50
        var SHORT_LIMIT = 25
            private set

        suspend fun getRecentManga(includeRead: Boolean = false, customAmount: Int = 0): List<Pair<Manga, Long>> {
            val presenter = RecentsPresenter()
            presenter.viewType = 1
            SHORT_LIMIT = when {
                customAmount > 0 -> (customAmount * 1.5).roundToInt()
                includeRead -> 50
                else -> 25
            }
            presenter.runRecents(limit = customAmount, includeReadAnyway = includeRead)
            SHORT_LIMIT = 25
            return presenter.recentItems
                .filter { it.mch.manga.id != null }
                .map { it.mch.manga to it.mch.history.last_read }
        }
    }
}
