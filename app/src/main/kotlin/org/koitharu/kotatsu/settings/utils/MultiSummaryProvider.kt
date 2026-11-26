package org.koitharu.kotatsu.settings.utils

import androidx.annotation.StringRes
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference

class MultiSummaryProvider(@StringRes private val emptySummaryId: Int) :
	Preference.SummaryProvider<MultiSelectListPreference> {

	override fun provideSummary(preference: MultiSelectListPreference): CharSequence {
		val values = preference.values
		if (values.isNullOrEmpty()) {
			return preference.context.getString(emptySummaryId)
		}
		val formatted = values.mapNotNull { value ->
			val index = preference.findIndexOfValue(value)
			if (index >= 0 && preference.entries != null) {
				preference.entries.getOrNull(index)
			} else {
				preference.entries?.firstOrNull { it.toString().equals(value, ignoreCase = true) }
					?: value
			}
		}.joinToString(", ")
		return formatted.ifEmpty { preference.context.getString(emptySummaryId) }
	}
}
