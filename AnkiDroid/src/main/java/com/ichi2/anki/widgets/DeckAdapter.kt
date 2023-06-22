/****************************************************************************************
 * Copyright (c) 2015 Houssam Salem <houssam.salem.au@gmail.com>                        *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki.widgets

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.CheckResult
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.servicelayer.DeckService.defaultDeckHasCards
import com.ichi2.annotations.NeedsTest
import com.ichi2.libanki.DeckId
import com.ichi2.libanki.sched.AbstractDeckTreeNode
import com.ichi2.libanki.sched.Counts
import com.ichi2.libanki.sched.TreeNode
import com.ichi2.libanki.sched.associateNodeWithParent
import com.ichi2.utils.KotlinCleanup
import com.ichi2.utils.TypedFilter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ankiweb.rsdroid.BackendFactory
import net.ankiweb.rsdroid.RustCleanup
import timber.log.Timber
import java.util.*

/**
 * RecyclerView.Adapter for the list of decks in the DeckPicker.
 *     @param mPartiallyTransparentForBackground  Whether we have a background (so some items should be partially transparent).

 */
@KotlinCleanup("lots to do")
@RustCleanup("Lots of bad code: should not be using suspend functions inside an adapter")
@RustCleanup("Differs from legacy backend: Create deck 'One', create deck 'One::two'. 'One::two' was not expanded")
class DeckAdapter(
    private val layoutInflater: LayoutInflater,
    context: Context,
    private val partiallyTransparentForBackground: Boolean,
    // Listeners
    private val deckClickListener: View.OnClickListener,
    private val deckExpanderClickListener: View.OnClickListener,
    private val deckLongClickListener: OnLongClickListener,
    private val countsClickListener: View.OnClickListener
) : RecyclerView.Adapter<DeckAdapter.ViewHolder>(), Filterable {
    /**
     * A list of decks. Contains at least all decks that should be displayed while search is empty.
     * It may also contain collapsed decks.
     */
    private var deckList: List<TreeNode<AbstractDeckTreeNode>> = ArrayList()

    /** The subset of mDeckList that should be displayed as a result of current search.
     * It contains decks that match this search and their parents. */
    private var currentDeckList: List<TreeNode<AbstractDeckTreeNode>> = ArrayList()

    /**
     *  color used to display the number of cards if it is 0 in the trailing column of deck picker.
     */
    private val zeroCountColor: Int

    /**
     * Color used for the number of new cards to review today.
     */
    private val newCountColor: Int

    /**
     * Color used for the number of cards in learning to see today.
     */
    private val learnCountColor: Int

    /**
     * Color used for the number of card in review mode to see today.
     */
    private val reviewCountColor: Int

    /**
     * Color for the background of the currently selected deck.
     */
    private val rowCurrentDrawable: Int

    /**
     * Color for the text of the standard decks (not dynamic).
     */
    private val deckNameDefaultColor: Int

    /**
     * Color for the text of dynamic decks.
     */
    private val deckNameDynColor: Int

    /**
     * The button showing a deck is collapsed and offering to expand it.
     */
    private val expandImage: Drawable

    /**
     * The button showing a deck is expanded, and offer to collapse it.
     */
    private val collapseImage: Drawable
    private var currentDeckId: DeckId = 0

    // Totals accumulated as each deck is processed. Their value should be ignored until [mNumbersComputed] is true.
    private var numberOfNewCardsToReview = 0
    private var numberOfCardsInLearningToReview = 0
    private var numberOfReviewCardsToReview = 0
    private var numbersComputed = false

    // Flags
    /**
     * Whether any deck of the collection has a subdeck.
     */
    private var hasSubdecks = false

    private var deckIdToParentMap = mapOf<DeckId, DeckId?>()

    // ViewHolder class to save inflated views for recycling
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val deckLayout: RelativeLayout
        val countsLayout: LinearLayout
        val deckExpander: ImageButton
        val indentView: ImageButton
        val deckName: TextView
        val deckNew: TextView
        val deckLearn: TextView
        val deckRev: TextView

        init {
            deckLayout = v.findViewById(R.id.DeckPickerHoriz)
            countsLayout = v.findViewById(R.id.counts_layout)
            deckExpander = v.findViewById(R.id.deckpicker_expander)
            indentView = v.findViewById(R.id.deckpicker_indent)
            deckName = v.findViewById(R.id.deckpicker_name)
            deckNew = v.findViewById(R.id.deckpicker_new)
            deckLearn = v.findViewById(R.id.deckpicker_lrn)
            deckRev = v.findViewById(R.id.deckpicker_rev)
        }
    }

    private val mutex = Mutex()

    /**
     * Consume a list of [AbstractDeckTreeNode]s to render a new deck list.
     * @param filter The string to filter the deck by
     */
    @NeedsTest("Ensure hasSubdecks is false if there are only top level decks")
    suspend fun buildDeckList(nodes: List<TreeNode<AbstractDeckTreeNode>>, filter: CharSequence?) {
        Timber.d("buildDeckList")
        // TODO: This is a lazy hack to fix a bug. We hold the lock for far too long
        // and do I/O inside it. Better to calculate the new lists outside the lock, then swap
        mutex.withLock {
            hasSubdecks = nodes.any { it.children.any() }
            currentDeckId = withCol { decks.current().optLong("id") }
            val newDecks = processNodes(nodes)
            deckList = newDecks.toList()
            currentDeckList = newDecks.toList()

            val topLevelNodes = nodes.filter { it.value.depth == 0 && it.value.shouldDisplayCounts() }
            numberOfReviewCardsToReview = topLevelNodes.sumOf { it.value.revCount }
            numberOfCardsInLearningToReview = topLevelNodes.sumOf { it.value.lrnCount }
            numberOfNewCardsToReview = topLevelNodes.sumOf { it.value.newCount }
            numbersComputed = true
            // Note: this will crash if we have a deck list with identical DeckIds
            deckIdToParentMap = nodes.associateNodeWithParent().entries.associate { Pair(it.key.did, it.value?.did) }.toMap()
            // Filtering performs notifyDataSetChanged after the async work is complete
            getFilter().filter(filter)
        }
    }

    fun getNodeByDid(did: DeckId): TreeNode<AbstractDeckTreeNode> {
        val pos = findDeckPosition(did)
        return currentDeckList[pos]
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = layoutInflater.inflate(R.layout.deck_item, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Update views for this node
        val treeNode = currentDeckList[position]
        val node = treeNode.value
        // Set the expander icon and padding according to whether or not there are any subdecks
        val deckLayout = holder.deckLayout
        val rightPadding = deckLayout.resources.getDimension(R.dimen.deck_picker_right_padding).toInt()
        if (hasSubdecks) {
            val smallPadding = deckLayout.resources.getDimension(R.dimen.deck_picker_left_padding_small).toInt()
            deckLayout.setPadding(smallPadding, 0, rightPadding, 0)
            holder.deckExpander.visibility = View.VISIBLE
            // Create the correct expander for this deck
            runBlocking { setDeckExpander(holder.deckExpander, holder.indentView, treeNode) }
        } else {
            holder.deckExpander.visibility = View.GONE
            val normalPadding = deckLayout.resources.getDimension(R.dimen.deck_picker_left_padding).toInt()
            deckLayout.setPadding(normalPadding, 0, rightPadding, 0)
        }
        if (treeNode.hasChildren()) {
            holder.deckExpander.tag = node.did
            holder.deckExpander.setOnClickListener(deckExpanderClickListener)
        } else {
            holder.deckExpander.isClickable = false
            holder.deckExpander.setOnClickListener(null)
        }
        holder.deckLayout.setBackgroundResource(rowCurrentDrawable)
        // Set background colour. The current deck has its own color
        if (isCurrentlySelectedDeck(node)) {
            holder.deckLayout.setBackgroundResource(rowCurrentDrawable)
            if (partiallyTransparentForBackground) {
                setBackgroundAlpha(holder.deckLayout, SELECTED_DECK_ALPHA_AGAINST_BACKGROUND)
            }
        } else {
            // Ripple effect
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = holder.deckLayout.context.obtainStyledAttributes(attrs)
            holder.deckLayout.setBackgroundResource(ta.getResourceId(0, 0))
            ta.recycle()
        }
        // Set deck name and colour. Filtered decks have their own colour
        holder.deckName.text = node.lastDeckNameComponent
        val filtered = if (!BackendFactory.defaultLegacySchema) {
            node.filtered
        } else {
            runBlocking { withCol { decks.isDyn(node.did) } }
        }
        if (filtered) {
            holder.deckName.setTextColor(deckNameDynColor)
        } else {
            holder.deckName.setTextColor(deckNameDefaultColor)
        }

        // Set the card counts and their colors
        if (node.shouldDisplayCounts()) {
            holder.deckNew.text = node.newCount.toString()
            holder.deckNew.setTextColor(if (node.newCount == 0) zeroCountColor else newCountColor)
            holder.deckLearn.text = node.lrnCount.toString()
            holder.deckLearn.setTextColor(if (node.lrnCount == 0) zeroCountColor else learnCountColor)
            holder.deckRev.text = node.revCount.toString()
            holder.deckRev.setTextColor(if (node.revCount == 0) zeroCountColor else reviewCountColor)
        }

        // Store deck ID in layout's tag for easy retrieval in our click listeners
        holder.deckLayout.tag = node.did
        holder.countsLayout.tag = node.did

        // Set click listeners
        holder.deckLayout.setOnClickListener(deckClickListener)
        holder.deckLayout.setOnLongClickListener(deckLongClickListener)
        holder.countsLayout.setOnClickListener(countsClickListener)
    }

    private fun setBackgroundAlpha(view: View, alphaPercentage: Double) {
        val background = view.background.mutate()
        background.alpha = (255 * alphaPercentage).toInt()
        view.background = background
    }

    private fun isCurrentlySelectedDeck(node: AbstractDeckTreeNode): Boolean {
        return node.did == currentDeckId
    }

    override fun getItemCount(): Int {
        return currentDeckList.size
    }

    @RustCleanup("non suspend")
    private suspend fun setDeckExpander(expander: ImageButton, indent: ImageButton, node: TreeNode<AbstractDeckTreeNode>) {
        val nodeValue = node.value
        val collapsed = if (BackendFactory.defaultLegacySchema) {
            withCol { decks.get(nodeValue.did).optBoolean("collapsed", false) }
        } else {
            node.value.collapsed
        }
        // Apply the correct expand/collapse drawable
        if (node.hasChildren()) {
            expander.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (collapsed) {
                expander.setImageDrawable(expandImage)
                expander.contentDescription = expander.context.getString(R.string.expand)
            } else {
                expander.setImageDrawable(collapseImage)
                expander.contentDescription = expander.context.getString(R.string.collapse)
            }
        } else {
            expander.visibility = View.INVISIBLE
            expander.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Add some indenting for each nested level
        val width = indent.resources.getDimension(R.dimen.keyline_1).toInt() * nodeValue.depth
        indent.minimumWidth = width
    }

    /**
     * Returns a filtered and flattened view of [nodes]
     * [nodes] contains all nodes of depth 0.
     * Afterwards, all depths are returned
     */
    @CheckResult
    private suspend fun processNodes(nodes: List<TreeNode<AbstractDeckTreeNode>>): List<TreeNode<AbstractDeckTreeNode>> {
        val result = mutableListOf<TreeNode<AbstractDeckTreeNode>>()
        for (node in nodes) {
            if (BackendFactory.defaultLegacySchema) {
                // If the default deck is empty, hide it by not adding it to the deck list.
                // We don't hide it if it's the only deck or if it has sub-decks.
                if (node.value.did == 1L && nodes.size > 1 && !node.hasChildren()) {
                    if (withCol { !defaultDeckHasCards(col) }) {
                        continue
                    }
                }
            }
            val isCollapsed = if (BackendFactory.defaultLegacySchema) {
                withCol { decks.get(node.value.did).optBoolean("collapsed") }
            } else {
                // backend takes care of excluding default, and includes collapsed info
                node.value.collapsed
            }

            result.add(node)

            // Process sub-decks
            if (!isCollapsed) {
                result.addAll(processNodes(node.children))
            }
        }
        return result
    }

    /**
     * Return the position of the deck in the deck list. If the deck is a child of a collapsed deck
     * (i.e., not visible in the deck list), then the position of the parent deck is returned instead.
     *
     * An invalid deck ID will return position 0.
     */
    fun findDeckPosition(did: DeckId): Int {
        currentDeckList.forEachIndexed { index, treeNode ->
            if (treeNode.value.did == did) {
                return index
            }
        }

        // If the deck is not in our list, we search again using the immediate parent
        // If the deck is not found, return 0
        val parentDeckId = deckIdToParentMap[did] ?: return 0
        return findDeckPosition(parentDeckId)
    }

    suspend fun eta(): Int? = if (numbersComputed) {
        withCol { sched.eta(Counts(numberOfNewCardsToReview, numberOfCardsInLearningToReview, numberOfReviewCardsToReview)) }
    } else {
        null
    }

    val due: Int?
        get() = if (numbersComputed) {
            numberOfNewCardsToReview + numberOfCardsInLearningToReview + numberOfReviewCardsToReview
        } else {
            null
        }
    override fun getFilter(): Filter {
        return DeckFilter(deckList)
    }

    @VisibleForTesting
    inner class DeckFilter(deckList: List<TreeNode<AbstractDeckTreeNode>>) : TypedFilter<TreeNode<AbstractDeckTreeNode>>(deckList) {
        override fun filterResults(constraint: CharSequence, items: List<TreeNode<AbstractDeckTreeNode>>): List<TreeNode<AbstractDeckTreeNode>> {
            val filterPattern = constraint.toString().lowercase(Locale.getDefault()).trim { it <= ' ' }
            return items.mapNotNull { t: TreeNode<AbstractDeckTreeNode> -> filterDeckInternal(filterPattern, t) }
        }

        override fun publishResults(constraint: CharSequence?, results: List<TreeNode<AbstractDeckTreeNode>>) {
            currentDeckList = results.toList()
            notifyDataSetChanged()
        }

        private fun filterDeckInternal(filterPattern: String, root: TreeNode<AbstractDeckTreeNode>): TreeNode<AbstractDeckTreeNode>? {
            // If a deck contains the string, then all its children are valid
            if (containsFilterString(filterPattern, root.value)) {
                return root
            }
            val children = root.children
            val ret: MutableList<TreeNode<AbstractDeckTreeNode>> = ArrayList(children.size)
            for (child in children) {
                val returned = filterDeckInternal(filterPattern, child)
                if (returned != null) {
                    ret.add(returned)
                }
            }

            // If any of a deck's children contains the search string, then the deck is valid
            if (ret.isEmpty()) return null

            // we have a root, and a list of trees with the counts already calculated.
            return TreeNode(root.value).apply {
                this.children.addAll(ret)
            }
        }

        private fun containsFilterString(filterPattern: String, root: AbstractDeckTreeNode): Boolean {
            val deckName = root.fullDeckName
            return deckName.lowercase(Locale.getDefault()).contains(filterPattern) || deckName.lowercase(Locale.ROOT).contains(filterPattern)
        }
    }

    companion object {
        /* Make the selected deck roughly half transparent if there is a background */
        const val SELECTED_DECK_ALPHA_AGAINST_BACKGROUND = 0.45
    }

    init {
        // Get the colors from the theme attributes
        val attrs = intArrayOf(
            R.attr.zeroCountColor,
            R.attr.newCountColor,
            R.attr.learnCountColor,
            R.attr.reviewCountColor,
            R.attr.currentDeckBackground,
            android.R.attr.textColor,
            R.attr.dynDeckColor,
            R.attr.expandRef,
            R.attr.collapseRef
        )
        val ta = context.obtainStyledAttributes(attrs)
        zeroCountColor = ta.getColor(0, ContextCompat.getColor(context, R.color.black))
        newCountColor = ta.getColor(1, ContextCompat.getColor(context, R.color.black))
        learnCountColor = ta.getColor(2, ContextCompat.getColor(context, R.color.black))
        reviewCountColor = ta.getColor(3, ContextCompat.getColor(context, R.color.black))
        rowCurrentDrawable = ta.getResourceId(4, 0)
        deckNameDefaultColor = ta.getColor(5, ContextCompat.getColor(context, R.color.black))
        deckNameDynColor = ta.getColor(6, ContextCompat.getColor(context, R.color.material_blue_A700))
        expandImage = ta.getDrawable(7)!!
        expandImage.isAutoMirrored = true
        collapseImage = ta.getDrawable(8)!!
        collapseImage.isAutoMirrored = true
        ta.recycle()
    }
}
