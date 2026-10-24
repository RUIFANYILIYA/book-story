package dev.ebookd.reader.ui.shelf

/**
 * The reader's own state for a book, in the two vocabularies that need to agree.
 *
 * The server stores an enum (`none`, `reading`, `finished`, `abandoned`, `on_hold`) and the reader
 * reads Chinese, so the pair lives in one place: a status that is spelled here and there is a status
 * that gets marked one way and badged another.
 */
enum class ReadingStatus(
    val code: String,
    val label: String,
) {
    READING("reading", "在读"),
    FINISHED("finished", "已读"),
    ON_HOLD("on_hold", "搁置"),
    ABANDONED("abandoned", "放弃"),
    ;

    companion object {

        /**
         * The word to put on a card, or null for nothing to put there.
         *
         * `none` is deliberately absent: it is the absence of a state rather than a state, and a
         * shelf where every cover carries a word is a shelf where none of them do.
         */
        fun badge(code: String?): String? = values().firstOrNull { it.code == code }?.label

        /** The menu's choices, including the way back to no state at all. */
        val menu: List<Pair<String, String>> = values().map { it.code to it.label } + ("none" to "未开始")
    }
}
