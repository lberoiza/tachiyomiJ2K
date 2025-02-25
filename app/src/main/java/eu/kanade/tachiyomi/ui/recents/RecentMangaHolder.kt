package eu.kanade.tachiyomi.ui.recents

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.image.coil.loadManga
import eu.kanade.tachiyomi.databinding.RecentMangaItemBinding
import eu.kanade.tachiyomi.databinding.RecentSubChapterItemBinding
import eu.kanade.tachiyomi.ui.download.DownloadButton
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.timeSpanFromNow
import eu.kanade.tachiyomi.util.view.setAnimVectorCompat
import eu.kanade.tachiyomi.util.view.setCards

class RecentMangaHolder(
    view: View,
    val adapter: RecentMangaAdapter,
) : BaseChapterHolder(view, adapter) {

    private val binding = RecentMangaItemBinding.bind(view)
    var chapterId: Long? = null
    private val isUpdates
        get() = adapter.viewType == RecentsPresenter.VIEW_TYPE_ONLY_UPDATES

    private val isSmallUpdates
        get() = isUpdates && !adapter.showUpdatedTime

    init {
        binding.cardLayout.setOnClickListener { adapter.delegate.onCoverClick(flexibleAdapterPosition) }
        binding.removeHistory.setOnClickListener { adapter.delegate.onRemoveHistoryClicked(flexibleAdapterPosition) }
        binding.showMoreChapters.setOnClickListener {
            val moreVisible = !binding.moreChaptersLayout.isVisible
            binding.moreChaptersLayout.isVisible = moreVisible
            adapter.delegate.updateExpandedExtraChapters(flexibleAdapterPosition, moreVisible)
            binding.showMoreChapters.setAnimVectorCompat(
                if (moreVisible) {
                    R.drawable.anim_expand_more_to_less
                } else {
                    R.drawable.anim_expand_less_to_more
                },
            )
            if (moreVisible) {
                binding.moreChaptersLayout.children.forEach { view ->
                    RecentSubChapterItemBinding.bind(view).updateDivider()
                }
            }
            if (isUpdates && binding.moreChaptersLayout.children.any { view ->
                !RecentSubChapterItemBinding.bind(view).subtitle.text.isNullOrBlank()
            }
            ) {
                showScanlatorInBody(moreVisible)
            }
            binding.endView.updateLayoutParams<ViewGroup.LayoutParams> {
                height = binding.mainView.height
            }
            val transition = TransitionSet()
                .addTransition(androidx.transition.ChangeBounds())
                .addTransition(androidx.transition.Slide())
            transition.duration = it.resources.getInteger(android.R.integer.config_shortAnimTime)
                .toLong()
            TransitionManager.beginDelayedTransition(adapter.recyclerView, transition)
        }
        updateCards()
        binding.frontView.layoutTransition?.enableTransitionType(LayoutTransition.APPEARING)
    }

    fun updateCards() {
        setCards(adapter.showOutline, binding.card, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind(item: RecentMangaItem) {
        val showDLs = adapter.showDownloads
        binding.mainView.transitionName = "recents chapter $bindingAdapterPosition transition"
        val showRemoveHistory = adapter.showRemoveHistory
        val showTitleFirst = adapter.showTitleFirst
        binding.downloadButton.downloadButton.isVisible = when (showDLs) {
            RecentMangaAdapter.ShowRecentsDLs.None -> false
            RecentMangaAdapter.ShowRecentsDLs.OnlyUnread, RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded -> !item.chapter.read
            RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded -> true
            RecentMangaAdapter.ShowRecentsDLs.All -> true
        } && !item.mch.manga.isLocal()

        binding.cardLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = (if (isSmallUpdates) 40 else 80).dpToPx
            width = (if (isSmallUpdates) 40 else 60).dpToPx
        }
        listOf(binding.title, binding.subtitle).forEach {
            it.updateLayoutParams<ConstraintLayout.LayoutParams> {
                if (isSmallUpdates) {
                    if (it == binding.title) topMargin = 5.dpToPx
                    endToStart = R.id.button_layout
                    endToEnd = -1
                } else {
                    if (it == binding.title) topMargin = 2.dpToPx
                    endToStart = -1
                    endToEnd = R.id.main_view
                }
            }
        }
        binding.buttonLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (isSmallUpdates) {
                topToBottom = -1
                topToTop = R.id.card_layout
                bottomToBottom = R.id.card_layout
                topMargin = 4.dpToPx
            } else {
                topToTop = -1
                topToBottom = R.id.subtitle
                bottomToBottom = R.id.main_view
                topMargin = 0
            }
        }
        val freeformCovers = !isSmallUpdates && !adapter.uniformCovers
        with(binding.coverThumbnail) {
            adjustViewBounds = freeformCovers
            scaleType = if (!freeformCovers) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        listOf(binding.coverThumbnail, binding.card).forEach {
            it.updateLayoutParams<ViewGroup.LayoutParams> {
                width = if (!freeformCovers) {
                    ViewGroup.LayoutParams.MATCH_PARENT
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
        }

        binding.removeHistory.isVisible = item.mch.history.id != null && showRemoveHistory
        val context = itemView.context
        val chapterName =
            item.chapter.preferredChapterName(context, item.mch.manga, adapter.preferences)

        binding.title.apply {
            text = if (!showTitleFirst) {
                chapterName
            } else {
                item.mch.manga.title
            }
            ChapterUtil.setTextViewForChapter(this, item)
        }
        binding.subtitle.apply {
            text = if (!showTitleFirst) {
                item.mch.manga.title
            } else {
                chapterName
            }
            setTextColor(ChapterUtil.readColor(context, item))
        }
        if (binding.frontView.translationX == 0f) {
            binding.read.setImageResource(
                if (item.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
            )
        }
        val notValidNum = item.mch.chapter.chapter_number <= 0
        binding.body.isVisible = !isSmallUpdates
        binding.body.text = when {
            item.mch.chapter.id == null -> context.timeSpanFromNow(R.string.added_, item.mch.manga.date_added)
            isSmallUpdates -> ""
            item.mch.history.id == null -> {
                if (adapter.viewType == RecentsPresenter.VIEW_TYPE_ONLY_UPDATES) {
                    if (adapter.sortByFetched) {
                        context.timeSpanFromNow(R.string.fetched_, item.chapter.date_fetch)
                    } else {
                        context.timeSpanFromNow(R.string.updated_, item.chapter.date_upload)
                    }
                } else {
                    context.timeSpanFromNow(R.string.fetched_, item.chapter.date_fetch) + "\n" +
                        context.timeSpanFromNow(R.string.updated_, item.chapter.date_upload)
                }
            }
            item.chapter.id != item.mch.chapter.id -> context.timeSpanFromNow(R.string.read_, item.mch.history.last_read) +
                "\n" + binding.body.context.getString(
                if (notValidNum) R.string.last_read_ else R.string.last_read_chapter_,
                if (notValidNum) item.mch.chapter.name else adapter.decimalFormat.format(item.mch.chapter.chapter_number),
            )
            item.chapter.pages_left > 0 && !item.chapter.read -> context.timeSpanFromNow(R.string.read_, item.mch.history.last_read) +
                "\n" + itemView.resources.getQuantityString(
                R.plurals.pages_left,
                item.chapter.pages_left,
                item.chapter.pages_left,
            )
            else -> context.timeSpanFromNow(R.string.read_, item.mch.history.last_read)
        }
        if ((context as? Activity)?.isDestroyed != true) {
            binding.coverThumbnail.loadManga(item.mch.manga)
        }
        if (!item.mch.manga.isLocal()) {
            notifyStatus(
                if (adapter.isSelected(flexibleAdapterPosition)) Download.State.CHECKED else item.status,
                item.progress,
                item.chapter.read,
            )
        }

        binding.showMoreChapters.isVisible = item.mch.extraChapters.isNotEmpty()
        binding.moreChaptersLayout.isVisible = binding.showMoreChapters.isVisible &&
            adapter.delegate.areExtraChaptersExpanded(flexibleAdapterPosition)
        val moreVisible = binding.moreChaptersLayout.isVisible
        binding.showMoreChapters.setImageResource(
            if (moreVisible) {
                R.drawable.ic_expand_less_24dp
            } else {
                R.drawable.ic_expand_more_24dp
            },
        )
        val extraIds = binding.moreChaptersLayout.children.map {
            it.findViewById<DownloadButton>(R.id.download_button)?.tag
        }.toList()
        if (extraIds == item.mch.extraChapters.map { it.id }) {
            item.mch.extraChapters.forEachIndexed { index, chapter ->
                RecentSubChapterItemBinding.bind(binding.moreChaptersLayout.getChildAt(index))
                    .configureView(chapter, item)
            }
            if (isUpdates && binding.moreChaptersLayout.children.any { view ->
                !RecentSubChapterItemBinding.bind(view).subtitle.text.isNullOrBlank()
            }
            ) {
                showScanlatorInBody(moreVisible, item)
            }
        } else {
            binding.moreChaptersLayout.removeAllViews()
            var hasSameChapter = false
            if (item.mch.extraChapters.isNotEmpty()) {
                item.mch.extraChapters.forEach { chapter ->
                    val binding = RecentSubChapterItemBinding.inflate(
                        LayoutInflater.from(context),
                        binding.moreChaptersLayout,
                        true,
                    )
                    binding.configureView(chapter, item)
                    if (isUpdates && chapter.isRecognizedNumber &&
                        chapter.chapter_number == item.chapter.chapter_number &&
                        !chapter.scanlator.isNullOrBlank()
                    ) {
                        binding.subtitle.text = chapter.scanlator
                        binding.subtitle.isVisible = true
                        if (!hasSameChapter) {
                            showScanlatorInBody(moreVisible, item)
                            hasSameChapter = true
                        }
                    }
                }
            } else {
                chapterId = null
            }
        }
        listOf(binding.mainView, binding.downloadButton.root, binding.showMoreChapters, binding.cardLayout).forEach {
            it.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    binding.endView.translationY = binding.mainView.y
                    binding.endView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = binding.mainView.height
                    }
                    binding.read.setImageResource(
                        if (item.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
                    )
                    chapterId = null
                }
                false
            }
        }
    }

    private fun showScanlatorInBody(add: Boolean, originalItem: RecentMangaItem? = null) {
        val item = originalItem ?: adapter.getItem(bindingAdapterPosition) as? RecentMangaItem ?: return
        val originalText = binding.body.text
        binding.body.maxLines = 2
        val scanlator = item.chapter.scanlator ?: return
        if (add) {
            if (isSmallUpdates) {
                binding.body.maxLines = 1
                binding.body.text = item.chapter.scanlator
                binding.body.isVisible = true
            } else if (!originalText.contains(scanlator)) {
                val text = "$originalText\n$scanlator"
                binding.body.text = text
            }
        } else {
            if (isSmallUpdates) {
                binding.body.isVisible = false
            } else {
                binding.body.text = originalText.removeSuffix("\n$scanlator")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun RecentSubChapterItemBinding.configureView(chapter: Chapter, item: RecentMangaItem) {
        val context = itemView.context
        val showDLs = adapter.showDownloads
        title.text = chapter.preferredChapterName(context, item.mch.manga, adapter.preferences)
        title.setTextColor(ChapterUtil.readColor(context, chapter))
        chapter.dateRead?.let { dateRead ->
            subtitle.text = context.timeSpanFromNow(R.string.read_, dateRead)
            subtitle.isVisible = true
        }
        root.setOnClickListener {
            adapter.delegate.onSubChapterClicked(
                bindingAdapterPosition,
                chapter,
                it,
            )
        }
        root.setOnLongClickListener {
            adapter.delegate.onItemLongClick(bindingAdapterPosition, chapter)
        }
        listOf(root, downloadButton.root).forEach {
            it.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    binding.read.setImageResource(
                        if (chapter.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
                    )
                    binding.endView.translationY = binding.moreChaptersLayout.y + root.y
                    binding.endView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = root.height
                    }
                    chapterId = chapter.id
                }
                false
            }
        }
        textLayout.updatePaddingRelative(start = if (isSmallUpdates) 64.dpToPx else 84.dpToPx)
        updateDivider()
        root.transitionName = "recents sub chapter ${chapter.id ?: 0L} transition"
        root.tag = "sub ${chapter.id}"
        downloadButton.root.tag = chapter.id
        val downloadInfo =
            item.downloadInfo.find { it.chapterId == chapter.id } ?: return
        downloadButton.downloadButton.setOnClickListener {
            downloadOrRemoveMenu(it, chapter, downloadInfo.status)
        }
        downloadButton.downloadButton.isVisible = when (showDLs) {
            RecentMangaAdapter.ShowRecentsDLs.None -> false
            RecentMangaAdapter.ShowRecentsDLs.OnlyUnread, RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded -> !chapter.read
            RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded -> true
            RecentMangaAdapter.ShowRecentsDLs.All -> true
        } && !item.mch.manga.isLocal()
        notifySubStatus(
            chapter,
            if (adapter.isSelected(flexibleAdapterPosition)) {
                Download.State.CHECKED
            } else {
                downloadInfo.status
            },
            downloadInfo.progress,
            chapter.read,
        )
    }

    private fun RecentSubChapterItemBinding.updateDivider() {
        divider.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = if (isSmallUpdates) 64.dpToPx else 84.dpToPx
        }
    }

    override fun onLongClick(view: View?): Boolean {
        super.onLongClick(view)
        val item = adapter.getItem(flexibleAdapterPosition) as? RecentMangaItem ?: return false
        return item.mch.history.id != null
    }

    fun notifyStatus(status: Download.State, progress: Int, isRead: Boolean, animated: Boolean = false) {
        binding.downloadButton.downloadButton.setDownloadStatus(status, progress, animated)
        val isChapterRead =
            if (adapter.showDownloads == RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded) isRead else true
        binding.downloadButton.downloadButton.isVisible =
            when (adapter.showDownloads) {
                RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded,
                RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded,
                ->
                    status !in Download.State.CHECKED..Download.State.NOT_DOWNLOADED || !isChapterRead
                else -> binding.downloadButton.downloadButton.isVisible
            }
    }

    fun notifySubStatus(chapter: Chapter, status: Download.State, progress: Int, isRead: Boolean, animated: Boolean = false) {
        val downloadButton = binding.moreChaptersLayout.findViewWithTag<DownloadButton>(chapter.id) ?: return
        downloadButton.setDownloadStatus(status, progress, animated)
        val isChapterRead =
            if (adapter.showDownloads == RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded) isRead else true
        downloadButton.isVisible =
            when (adapter.showDownloads) {
                RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded,
                RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded,
                ->
                    status !in Download.State.CHECKED..Download.State.NOT_DOWNLOADED || !isChapterRead
                else -> downloadButton.isVisible
            }
    }

    override fun getFrontView(): View {
        return if (chapterId == null) { binding.mainView } else {
            binding.moreChaptersLayout.children.find { it.tag == "sub $chapterId" }
                ?: binding.mainView
        }
    }

    override fun getRearEndView(): View {
        return binding.endView
    }
}
