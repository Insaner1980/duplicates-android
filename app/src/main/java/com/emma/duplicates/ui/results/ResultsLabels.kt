package com.emma.duplicates.ui.results

import com.emma.duplicates.R

internal fun ResultsFilter.labelRes(): Int =
    when (this) {
        ResultsFilter.ALL -> R.string.all
        ResultsFilter.PHOTOS -> R.string.photos
        ResultsFilter.VIDEOS -> R.string.videos
        ResultsFilter.AUDIO -> R.string.audio
        ResultsFilter.DOCUMENTS -> R.string.documents
    }

internal fun ResultsSort.labelRes(): Int =
    when (this) {
        ResultsSort.LARGEST_RECLAIMABLE -> R.string.sort_largest
        ResultsSort.MOST_COPIES -> R.string.sort_most_copies
        ResultsSort.FILE_NAME -> R.string.sort_file_name
        ResultsSort.NEWEST -> R.string.sort_newest
        ResultsSort.OLDEST -> R.string.sort_oldest
    }
