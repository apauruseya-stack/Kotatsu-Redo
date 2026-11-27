package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListImportUseCase
import org.koitharu.kotatsu.scrobbling.anilist.work.AniListImportWorker
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.ui.ScrobblerAuthHelper
import javax.inject.Inject

/** Everything that signs in to somewhere else. */
@AndroidEntryPoint
class AccountsSettingsFragment : BasePreferenceFragment(R.string.accounts_and_sync) {

	@Inject
	lateinit var scrobblerAuthHelper: ScrobblerAuthHelper

	@Inject
	lateinit var importUseCase: AniListImportUseCase

	@Inject
	lateinit var mangaSourcesRepository: MangaSourcesRepository

	@Inject
	lateinit var aniListSyncUseCase: org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncUseCase

	@Inject
	lateinit var aniListSyncLogStore: org.koitharu.kotatsu.scrobbling.anilist.data.AniListSyncLogStore

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_accounts)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		WorkManager.getInstance(requireContext())
			.getWorkInfosForUniqueWorkLiveData(AniListImportWorker.TAG)
			.observe(viewLifecycleOwner) { workInfos ->
				val isRunning = workInfos?.any {
					it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
				} == true
				val pref = findPreference<Preference>("anilist_import")
				if (isRunning) {
					pref?.setSummary(R.string.anilist_import_in_progress)
					pref?.isEnabled = false
				} else {
					pref?.setSummary(R.string.import_from_anilist_summary)
					pref?.isEnabled = scrobblerAuthHelper.isAuthorized(ScrobblerService.ANILIST)
				}
			}
	}

	override fun onResume() {
		super.onResume()
		bindScrobblerSummary(AppSettings.KEY_SHIKIMORI, ScrobblerService.SHIKIMORI)
		bindScrobblerSummary(AppSettings.KEY_ANILIST, ScrobblerService.ANILIST)
		bindScrobblerSummary(AppSettings.KEY_MAL, ScrobblerService.MAL)
		bindScrobblerSummary(AppSettings.KEY_KITSU, ScrobblerService.KITSU)

		val isAniListAuthed = scrobblerAuthHelper.isAuthorized(ScrobblerService.ANILIST)
		findPreference<Preference>("anilist_import")?.isEnabled = isAniListAuthed
		findPreference<Preference>("anilist_import_sources")?.isEnabled = isAniListAuthed
		findPreference<Preference>("anilist_sync_enabled")?.isEnabled = isAniListAuthed
		findPreference<Preference>("anilist_sync_now")?.isEnabled = isAniListAuthed && settings.isAniListSyncEnabled
		findPreference<Preference>("anilist_sync_log")?.isEnabled = isAniListAuthed
		updateSyncNowSummary()
		updateImportSourcesSummary()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			"anilist_import" -> {
				handleAniListImportClick()
				true
			}

			"anilist_import_sources" -> {
				showAniListImportSourcesDialog()
				true
			}

			"anilist_sync_now" -> {
				handleAniListSyncNowClick()
				true
			}

			"anilist_sync_log" -> {
				showAniListSyncLogDialog()
				true
			}

			AppSettings.KEY_SHIKIMORI -> {
				handleScrobblerClick(ScrobblerService.SHIKIMORI)
				true
			}

			AppSettings.KEY_MAL -> {
				handleScrobblerClick(ScrobblerService.MAL)
				true
			}

			AppSettings.KEY_ANILIST -> {
				handleScrobblerClick(ScrobblerService.ANILIST)
				true
			}

			AppSettings.KEY_KITSU -> {
				handleScrobblerClick(ScrobblerService.KITSU)
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun updateSyncNowSummary() {
		val pref = findPreference<Preference>("anilist_sync_now") ?: return
		val last = settings.aniListLastSyncTime
		if (last <= 0L) {
			pref.setSummary(R.string.anilist_sync_never)
		} else {
			val rel = android.text.format.DateUtils.getRelativeTimeSpanString(
				last,
				System.currentTimeMillis(),
				android.text.format.DateUtils.MINUTE_IN_MILLIS,
			)
			pref.summary = getString(R.string.anilist_sync_last, rel)
		}
	}

	private fun handleAniListSyncNowClick() {
		val pref = findPreference<Preference>("anilist_sync_now")
		pref?.setSummary(R.string.anilist_sync_in_progress)
		pref?.isEnabled = false
		viewLifecycleScope.launch {
			val result = aniListSyncUseCase.sync(force = true)
			pref?.isEnabled = true
			updateSyncNowSummary()
			when (result) {
				is org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncResult.Success -> {
					Snackbar.make(
						listView,
						getString(R.string.anilist_sync_success, result.updatedCount),
						Snackbar.LENGTH_SHORT,
					).show()
				}
				is org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncResult.AuthExpired -> {
					Snackbar.make(listView, "AniList session expired. Please sign in again.", Snackbar.LENGTH_LONG)
						.setAction(R.string.sign_in) {
							confirmScrobblerAuth(ScrobblerService.ANILIST)
						}.show()
				}
				is org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncResult.AlreadyRunning -> {
					Snackbar.make(listView, "Sync is already in progress", Snackbar.LENGTH_SHORT).show()
				}
				is org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncResult.Error -> {
					Snackbar.make(listView, R.string.anilist_sync_failed, Snackbar.LENGTH_LONG).show()
				}
				else -> Unit
			}
		}
	}

	private fun showAniListSyncLogDialog() {
		val entries = aniListSyncLogStore.entries.value
		if (entries.isEmpty()) {
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.anilist_sync_log)
				.setMessage(R.string.anilist_sync_log_empty)
				.setPositiveButton(android.R.string.ok, null)
				.show()
			return
		}

		val items = entries.map { entry ->
			val rel = android.text.format.DateUtils.getRelativeTimeSpanString(
				entry.timestamp,
				System.currentTimeMillis(),
				android.text.format.DateUtils.MINUTE_IN_MILLIS,
			)
			when (entry.status) {
				org.koitharu.kotatsu.scrobbling.anilist.data.AniListSyncStatus.SKIPPED_FAR_AHEAD ->
					"⚠️ ${entry.title}: Skipped remote Ch. ${entry.toChapter} (source has ${entry.sourceLatestChapter.toInt()}) [Tap to apply]\n$rel"
				org.koitharu.kotatsu.scrobbling.anilist.data.AniListSyncStatus.CLAMPED ->
					"${entry.title}: ${entry.fromChapter} → ${entry.toChapter} (clamped to latest)\n$rel"
				else ->
					"${entry.title}: ${entry.fromChapter} → ${entry.toChapter}\n$rel"
			}
		}.toTypedArray()

		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.anilist_sync_log)
			.setItems(items) { _, which ->
				val selected = entries.getOrNull(which) ?: return@setItems
				if (selected.canApplyAnyway) {
					MaterialAlertDialogBuilder(requireContext())
						.setTitle(selected.title)
						.setMessage("AniList shows progress at Ch. ${selected.toChapter}, but the source only has ${selected.sourceLatestChapter.toInt()} chapters. Would you like to apply this progress anyway?")
						.setPositiveButton("Apply anyway") { _, _ ->
							viewLifecycleScope.launch {
								val success = aniListSyncUseCase.forceApply(selected.mangaId, selected.toChapter)
								if (success) {
									Snackbar.make(listView, "Applied progress for ${selected.title}", Snackbar.LENGTH_SHORT).show()
								}
							}
						}
						.setNegativeButton(android.R.string.cancel, null)
						.show()
				}
			}
			.setNeutralButton(R.string.clear) { _, _ ->
				viewLifecycleScope.launch {
					aniListSyncLogStore.clear()
				}
			}
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	private fun bindScrobblerSummary(
		key: String,
		scrobblerService: ScrobblerService,
	) {
		val pref = findPreference<Preference>(key) ?: return
		if (!scrobblerAuthHelper.isAuthorized(scrobblerService)) {
			pref.setSummary(R.string.disabled)
			return
		}
		val username = scrobblerAuthHelper.getCachedUser(scrobblerService)?.nickname
		if (username != null) {
			pref.summary = getString(R.string.logged_in_as, username)
		} else {
			pref.setSummary(R.string.loading_)
			viewLifecycleScope.launch {
				pref.summary = withContext(Dispatchers.Default) {
					runCatching {
						val user = scrobblerAuthHelper.getUser(scrobblerService)
						getString(R.string.logged_in_as, user.nickname)
					}.getOrElse {
						it.printStackTraceDebug()
						it.getDisplayMessage(resources)
					}
				}
			}
		}
	}

	private fun handleScrobblerClick(scrobblerService: ScrobblerService) {
		if (!scrobblerAuthHelper.isAuthorized(scrobblerService)) {
			confirmScrobblerAuth(scrobblerService)
		} else {
			router.openScrobblerSettings(scrobblerService)
		}
	}

	private fun confirmScrobblerAuth(scrobblerService: ScrobblerService) {
		buildAlertDialog(context ?: return, isCentered = true) {
			setIcon(scrobblerService.iconResId)
			setTitle(scrobblerService.titleResId)
			setMessage(context.getString(R.string.scrobbler_auth_intro, context.getString(scrobblerService.titleResId)))
			setPositiveButton(R.string.sign_in) { _, _ ->
				scrobblerAuthHelper.startAuth(context, scrobblerService).onFailure {
					Snackbar.make(listView, it.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
				}
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun handleAniListImportClick() {
		if (!scrobblerAuthHelper.isAuthorized(ScrobblerService.ANILIST)) {
			confirmScrobblerAuth(ScrobblerService.ANILIST)
			return
		}

		val cachedPlan = importUseCase.loadCachedPlan()
		if (cachedPlan != null) {
			val message = buildString {
				append(getString(R.string.anilist_import_matched)).append(": ").append(cachedPlan.autoAcceptedCount).append("\n")
				append(getString(R.string.anilist_import_already_linked)).append(": ").append(cachedPlan.alreadyLinkedCount).append("\n")
				if (cachedPlan.reviewCount > 0) {
					append(getString(R.string.anilist_import_review)).append(": ").append(cachedPlan.reviewCount).append("\n")
				}
				if (cachedPlan.notFoundCount > 0) {
					append(getString(R.string.anilist_import_not_found)).append(": ").append(cachedPlan.notFoundCount).append("\n")
				}
				if (cachedPlan.skippedAdultCount > 0) {
					append(getString(R.string.anilist_import_skipped_adult, cachedPlan.skippedAdultCount)).append("\n")
				}
			}
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.anilist_import_preview_title)
				.setMessage(message)
				.setPositiveButton(R.string.anilist_import_confirm) { _, _ ->
					AniListImportWorker.enqueue(requireContext(), cachedPlan)
					Snackbar.make(listView, R.string.anilist_import_in_progress, Snackbar.LENGTH_LONG).show()
				}
				.setNeutralButton(R.string.reset) { _, _ ->
					importUseCase.clearCachedPlan()
					AniListImportWorker.enqueue(requireContext())
					Snackbar.make(listView, R.string.anilist_import_in_progress, Snackbar.LENGTH_LONG).show()
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		} else {
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.import_from_anilist)
				.setMessage(R.string.anilist_import_confirm_msg)
				.setPositiveButton(R.string.start_import) { _, _ ->
					AniListImportWorker.enqueue(requireContext())
					Snackbar.make(listView, R.string.anilist_import_in_progress, Snackbar.LENGTH_LONG).show()
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		}
	}

	private fun updateImportSourcesSummary() {
		val pref = findPreference<Preference>("anilist_import_sources") ?: return
		viewLifecycleScope.launch {
			val enabled = withContext(Dispatchers.IO) {
				mangaSourcesRepository.getEnabledSources()
			}
			val configured = settings.aniListImportSourceNames
			val summary = if (configured.isNullOrEmpty()) {
				if (enabled.isNotEmpty()) {
					enabled.take(3).joinToString { it.getTitle(requireContext()) } + (if (enabled.size > 3) " +${enabled.size - 3}" else "")
				} else {
					getString(R.string.anilist_import_sources_summary)
				}
			} else {
				val selected = enabled.filter { it.name in configured }
				if (selected.isNotEmpty()) {
					selected.joinToString { it.getTitle(requireContext()) }
				} else {
					configured.joinToString()
				}
			}
			pref.summary = summary
		}
	}

	private fun showAniListImportSourcesDialog() {
		viewLifecycleScope.launch {
			val enabled = withContext(Dispatchers.IO) {
				mangaSourcesRepository.getEnabledSources()
			}
			if (enabled.isEmpty()) {
				Snackbar.make(listView, R.string.anilist_select_import_sources_empty, Snackbar.LENGTH_LONG).show()
				return@launch
			}

			val sourceNames = enabled.map { it.name }
			val sourceTitles = enabled.map { it.getTitle(requireContext()) }.toTypedArray()
			val currentSelected = settings.aniListImportSourceNames ?: sourceNames.toSet()
			val checkedItems = BooleanArray(enabled.size) { i ->
				sourceNames[i] in currentSelected
			}

			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.anilist_select_import_sources)
				.setMultiChoiceItems(sourceTitles, checkedItems) { _, which, isChecked ->
					checkedItems[which] = isChecked
				}
				.setPositiveButton(android.R.string.ok) { _, _ ->
					val selectedSet = HashSet<String>()
					for (i in checkedItems.indices) {
						if (checkedItems[i]) {
							selectedSet.add(sourceNames[i])
						}
					}
					if (selectedSet.isNotEmpty()) {
						settings.aniListImportSourceNames = selectedSet
						importUseCase.clearCachedPlan()
						updateImportSourcesSummary()
					} else {
						Snackbar.make(listView, R.string.anilist_select_import_sources_empty, Snackbar.LENGTH_LONG).show()
					}
				}
				.setNeutralButton(R.string.reset) { _, _ ->
					settings.aniListImportSourceNames = null
					importUseCase.clearCachedPlan()
					updateImportSourcesSummary()
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		}
	}
}
