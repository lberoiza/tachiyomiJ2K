package eu.kanade.tachiyomi.ui.migration

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import com.jakewharton.rxbinding.support.v7.widget.queryTextChangeEvents
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.main.BottomNavBarInterface
import eu.kanade.tachiyomi.ui.migration.manga.process.MigrationListController
import eu.kanade.tachiyomi.ui.source.global_search.GlobalSearchCardAdapter
import eu.kanade.tachiyomi.ui.source.global_search.GlobalSearchController
import eu.kanade.tachiyomi.ui.source.global_search.GlobalSearchPresenter
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class SearchController(
    private var manga: Manga? = null,
    private var sources: List<CatalogueSource>? = null
) : GlobalSearchController(
    manga?.originalTitle,
    bundle = bundleOf(
        OLD_MANGA to manga?.id,
        SOURCES to sources?.map { it.id }?.toLongArray()
    )
),
    BottomNavBarInterface {

    private var progress = 1
    var totalProgress = 0

    /**
     * Called when controller is initialized.
     */
    init {
        setHasOptionsMenu(true)
    }

    constructor(mangaId: Long, sources: LongArray) :
        this(
            Injekt.get<DatabaseHelper>().getManga(mangaId).executeAsBlocking(),
            sources.map { Injekt.get<SourceManager>().getOrStub(it) }.filterIsInstance<CatalogueSource>()
        )

    @Suppress("unused")
    constructor(bundle: Bundle) : this(
        bundle.getLong(OLD_MANGA),
        bundle.getLongArray(SOURCES) ?: LongArray(0)
    )

    override fun getTitle(): String? {
        if (totalProgress > 1) {
            return "($progress/$totalProgress) ${super.getTitle()}"
        } else {
            return super.getTitle()
        }
    }

    override fun createPresenter(): GlobalSearchPresenter {
        return SearchPresenter(initialQuery, manga!!, sources = sources)
    }

    /*override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (totalProgress > 1) {
            val menuItem = menu.add(Menu.NONE, 1, Menu.NONE, R.string.action_skip_manga)
            menuItem.icon = VectorDrawableCompat.create(resources!!, R.drawable
                .baseline_skip_next_white_24, null)
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                newManga = manga
                migrateManga()
            }
        }
        return true
    }*/

    fun migrateManga() {
        val target = targetController as? MigrationInterface ?: return
        val manga = manga ?: return
        val newManga = newManga ?: return

        val nextManga = target.migrateManga(manga, newManga, true)
        replaceWithNewSearchController(nextManga)
    }

    fun copyManga() {
        val target = targetController as? MigrationInterface ?: return
        val manga = manga ?: return
        val newManga = newManga ?: return

        val nextManga = target.migrateManga(manga, newManga, false)
        replaceWithNewSearchController(nextManga)
    }

    private fun replaceWithNewSearchController(manga: Manga?) {
        if (manga != null) {
            // router.popCurrentController()
            val searchController = SearchController(manga)
            searchController.targetController = targetController
            searchController.progress = progress + 1
            searchController.totalProgress = totalProgress
            router.replaceTopController(searchController.withFadeTransaction())
        } else router.popController(this)
    }

    override fun onMangaClick(manga: Manga) {
        if (targetController is MigrationListController) {
            val migrationListController = targetController as? MigrationListController
            val sourceManager: SourceManager by injectLazy()
            val source = sourceManager.get(manga.source) ?: return
            migrationListController?.useMangaForMigration(manga, source)
            router.popCurrentController()
            return
        }
    }

    override fun onMangaLongClick(position: Int, adapter: GlobalSearchCardAdapter) {
        // Call parent's default click listener
        val manga = adapter.getItem(position)?.manga ?: return
        super.onMangaClick(manga)
    }

    /**
     * Adds items to the options menu.
     *
     * @param menu menu containing options.
     * @param inflater used to load the menu xml.
     */
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // Inflate menu.
        inflater.inflate(R.menu.catalogue_new_list, menu)

        // Initialize search menu
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchItem.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                    searchView.onActionViewExpanded() // Required to show the query in the view
                    searchView.setQuery(presenter.query, false)
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                    return true
                }
            }
        )

        searchView.queryTextChangeEvents()
            .filter { it.isSubmitted }
            .subscribeUntilDestroy {
                presenter.search(it.queryText().toString())
                searchItem.collapseActionView()
                setTitle() // Update toolbar title
            }
    }

    override fun canChangeTabs(block: () -> Unit): Boolean {
        val migrationListController = router.getControllerWithTag(MigrationListController.TAG)
            as? BottomNavBarInterface
        if (migrationListController != null) return migrationListController.canChangeTabs(block)
        return true
    }

    companion object {
        const val OLD_MANGA = "old_manga"
        const val SOURCES = "sources"
    }
}
