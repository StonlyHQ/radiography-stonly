@file:OptIn(UiToolingDataApi::class)

package radiography.internal

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.Ref
import androidx.compose.ui.tooling.data.*
import androidx.compose.ui.unit.IntRect
import radiography.internal.ComposeLayoutInfo.*

/**
 * Information about a Compose `LayoutNode`, extracted from a [Group] tree via [Group.layoutInfos].
 *
 * This is a useful layer of indirection from directly handling Groups because it allows us to
 * define our own notion of what an atomic unit of "composable" is independently from how Compose
 * actually represents things under the hood. When this changes in some future dev version, we
 * only need to update the "parsing" logic in this file.
 * It's also helpful since we actually gather data from multiple Groups for a single LayoutInfo,
 * so parsing them ahead of time into these objects means the visitor can be stateless.
 */
public sealed class ComposeLayoutInfo {
    public data class LayoutNodeInfo(
        val name: String,
        val bounds: IntRect,
        val modifiers: List<Modifier>,
        val children: Sequence<ComposeLayoutInfo>,
    ) : ComposeLayoutInfo()

    public data class SubcompositionInfo(
        val name: String,
        val bounds: IntRect,
        val children: Sequence<ComposeLayoutInfo>
    ) : ComposeLayoutInfo()

    public data class AndroidViewInfo(
        val view: View
    ) : ComposeLayoutInfo()
}

/**
 * A sequence that lazily parses [ComposeLayoutInfo]s from a [Group] tree.
 */
internal val Group.layoutInfos: Sequence<ComposeLayoutInfo> get() = computeLayoutInfos()

/**
 * Recursively parses [ComposeLayoutInfo]s from a [Group]. Groups form a tree and can contain different
 * type of nodes which represent function calls, arbitrary data stored directly in the slot table,
 * or just subtrees.
 *
 * This function walks the tree and collects only Groups which represent emitted values
 * ([NodeGroup]s). These either represent `LayoutNode`s (Compose's internal primitive for layout
 * algorithms) or classic Android views that the composition emitted. This function collapses all
 * the groups in between each of these nodes, but uses the top-most Group under the previous node
 * to derive the "name" of the [ComposeLayoutInfo]. The other [ComposeLayoutInfo] properties come directly off
 * [NodeGroup] values.
 */
private fun Group.computeLayoutInfos(
    parentName: String = ""
): Sequence<ComposeLayoutInfo> {
    val name = parentName.ifBlank { this.name }.orEmpty()
    // Things that we want to consider children of the current node, but aren't actually child nodes
    // as reported by Group.children.
    val irregularChildren = subComposedChildren(name) + androidViewChildren()

    // Certain composables produce an internal structure that is hard to read if we report it exactly.
    // Instead, we use heuristics to recognize subtrees that match certain expected structures and
    // aggregate them somewhat before reporting.
    tryParseSubcomposition(name, irregularChildren)
        ?.let { return it }
    tryParseAndroidView(name, irregularChildren)
        ?.let { return it }

    // This is an intermediate group that doesn't represent a LayoutNode, so we flatten by just
    // reporting its children without reporting a new subtree.
    if (this !is NodeGroup) {
        return children.asSequence()
            .flatMap { it.computeLayoutInfos(name) } + irregularChildren
    }

    val children = children.asSequence()
        // This node will "consume" the name, so reset it name to empty for children.
        .flatMap { it.computeLayoutInfos() }

    val layoutInfo = LayoutNodeInfo(
        name = name,
        bounds = box,
        modifiers = tranformModifiers(modifierInfo),
        children = children + irregularChildren,
    )
    return sequenceOf(layoutInfo)
}

public fun tranformModifiers(modifierInfo: List<ModifierInfo>): List<Modifier> {
    val list = mutableListOf<Modifier>()
    list.addAll(modifierInfo.map { it.modifier })
//    list.add(onGloballyPositioned)
    return list
}

public val onGloballyPositioned: Modifier = Modifier.onGloballyPositioned { coordinates ->
    Log.d("Stonly", "onGloballyPositioned VALUE: $coordinates")
    // This will be the size of the Column.
    coordinates.size
    // The position of the Column relative to the application window.
    coordinates.positionInWindow()
    // The position of the Column relative to the Compose root.
    coordinates.positionInRoot()
    // These will be the alignment lines provided to the layout (empty here for Column).
    coordinates.providedAlignmentLines
    // This will be a LayoutCoordinates instance corresponding to the parent of Column.
    coordinates.parentLayoutCoordinates
}

/**
 * Look for any `CompositionContext`s stored in this group. These will be rolled up into the
 * `SubcomposeLayout` if present, otherwise they will just be shown as regular children.
 * The compositionData val is marked as internal, and not intended for public consumption.
 * The returned [SubcompositionInfo]s should be collated by [tryParseSubcomposition].
 */
@OptIn(InternalComposeApi::class)
private fun Group.subComposedChildren(name: String): Sequence<SubcompositionInfo> =
    getCompositionContexts()
        .flatMap { it.tryGetComposers().asSequence() }
        .map { subcomposer ->
            SubcompositionInfo(
                name = name,
                bounds = box,
                children = subcomposer.compositionData.asTree().layoutInfos
            )
        }

/**
 * The `AndroidView` composable remembers a [Ref] to a special internal subclass of [ViewGroup] that
 * manages the wiring between the hosting android view and the child view. This function looks for
 * refs to views and returns them as [AndroidViewInfo]s to be collated with [tryParseAndroidView].
 *
 * Note that [Ref] is a public type – any third-party composable could also remember a ref to a
 * view, and it would be reported by this function. That would almost certainly be a code smell for
 * a number of reasons though, so we don't try to ignore those cases.
 */
private fun Group.androidViewChildren(): List<AndroidViewInfo> = data.mapNotNull { datum ->
    (datum as? Ref<*>)
        ?.value
        // The concrete type is actually an internal ViewGroup subclass that has all the wiring, but
        // ultimately it's still just a ViewGroup so this simple check works.
        ?.let { it as? ViewGroup }
        ?.let(::AndroidViewInfo)
}

/**
 * SubcomposeLayouts need to be handled specially, because all their subcompositions are always
 * logical children of their single LayoutNode. In order to render them so that the rendering
 * actually matches that logical structure, we need to reorganize the subtree a bit so
 * subcompositions are children of the layout node and not siblings of it.
 *
 * Note that there's no sure-fire way to actually detect a SubcomposeLayout. The best we can do is
 * use a heuristic. If any part of the heuristics don't match, then we fall back to treating the
 * group like any other.
 *
 * The heuristic we use is:
 * - Name of the group is "SubcomposeLayout".
 * - Has one or more subcompositions under it.
 * - Has exactly one LayoutNode child.
 * - That LayoutNode has no children of its own.
 */
private fun Group.tryParseSubcomposition(
    name: String,
    irregularChildren: Sequence<ComposeLayoutInfo>
): Sequence<ComposeLayoutInfo>? {
    if (this.name != "SubcomposeLayout") return null

    val (subcompositions, regularChildren) =
        (children.asSequence().flatMap { it.computeLayoutInfos(name) } + irregularChildren)
            .partition { it is SubcompositionInfo }
            .let {
                // There's no type-safe partition operator so we just cast.
                @Suppress("UNCHECKED_CAST")
                it as Pair<List<SubcompositionInfo>, List<ComposeLayoutInfo>>
            }

    if (subcompositions.isEmpty()) return null
    if (regularChildren.size != 1) return null

    val mainNode = regularChildren.single()
    if (mainNode !is LayoutNodeInfo) return null
    if (!mainNode.children.isEmpty()) return null

    // We can be pretty confident at this point that this is an actual SubcomposeLayout, so
    // expose its layout node as the parent of all its subcompositions.
    val subcompositionName = "<subcomposition of ${mainNode.name}>"
    return sequenceOf(
        mainNode.copy(children = subcompositions.asSequence()
            .map { it.copy(name = subcompositionName) }
        )
    )
}

/**
 * The AndroidView composable also needs to be special-cased. The actual android view is stored
 * in a Ref deep inside the hierarchy somewhere, but we want to expose it as the immediate child
 * of nearest common parent node that contains both the android view and the LayoutNode that is
 * used as a proxy to measure and lay it out in the composable.
 *
 * We can't rely on just the composable name, since any composable could be called "AndroidView",
 * so if any of the subtree parsing fails to match our expectations, we fallback to treating it
 * like any other group. Note that this heuristic isn't as strict as the subcomposition one, since
 * there's only one way to get an android view into a composition, so we can rely more heavily on
 * the presence of an actual android view. We still require there to be only one LayoutNode child,
 * otherwise it would be ambiguous which node we should report as the parent of the view.
 * We also require the common parent to be a CallGroup, since that is a valid assumption as of the
 * time of this writing and it saves us the additional logic of having to decide whether to return
 * this or the mainNode as the root of the subtree if this is a NodeGroup for some reason.
 *
 * Note that while this looks very similar to the [tryParseSubcomposition], that is probably
 * mostly coincidental, so it's probably not a good idea to factor out any abstractions. Since
 * they both rely on internal-only implementation details of how the Compose runtime happens to
 * work, either of them could change independently in the future, and it will be easier to update
 * the logic of both if that happens if they're completely independent.
 */
private fun Group.tryParseAndroidView(
    name: String,
    irregularChildren: Sequence<ComposeLayoutInfo>
): Sequence<ComposeLayoutInfo>? {
    if (this.name != "AndroidView") return null
    if (this !is CallGroup) return null

    val (androidViews, regularChildren) =
        (children.asSequence().flatMap { it.computeLayoutInfos(name) } + irregularChildren)
            .partition { it is AndroidViewInfo }
            .let {
                // There's no type-safe partition operator so we just cast.
                @Suppress("UNCHECKED_CAST")
                it as Pair<List<AndroidViewInfo>, List<ComposeLayoutInfo>>
            }

    if (androidViews.isEmpty()) return null
    if (regularChildren.size != 1) return null

    val mainNode = regularChildren.single()
    if (mainNode !is LayoutNodeInfo) return null

    // We can be pretty confident at this point that this is an actual AndroidView composable,
    // so expose its layout node as the parent of its actual view.
    return sequenceOf(mainNode.copy(children = mainNode.children + androidViews))
}

private fun Sequence<*>.isEmpty(): Boolean = !iterator().hasNext()
