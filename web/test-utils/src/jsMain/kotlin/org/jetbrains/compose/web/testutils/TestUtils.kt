package org.jetbrains.compose.web.testutils

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.dom.clear
import org.jetbrains.compose.web.internal.runtime.*
import org.w3c.dom.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

/**
 * This class provides a set of utils methods to simplify compose-web tests.
 * There is no need to create its instances manually.
 * @see [runTest]
 */
@ComposeWebExperimentalTestsApi
class TestScope : CoroutineScope by MainScope() {

    /**
     * It's used as a parent element for the composition.
     * It's added into the document's body automatically.
     */
    val root = "div".asHtmlElement()

    private var waitForRecompositionCompleteContinuation: Continuation<Unit>? = null
    private val childrenIterator = root.children.asList().listIterator()

    init {
        document.body!!.appendChild(root)
    }

    private fun onRecompositionComplete() {
        waitForRecompositionCompleteContinuation?.resume(Unit)
        waitForRecompositionCompleteContinuation = null
    }

    /**
     * Cleans up the [root] content.
     * Creates a new composition with a given Composable [content].
     */
    @ComposeWebExperimentalTestsApi
    fun composition(content: @Composable () -> Unit) {
        root.clear()

        renderTestComposable(root = root) {
            content()
        }
    }

    /**
     * Use this method to test the composition mounted at [root]
     *
     * @param root - the [Element] that will be the root of the DOM tree managed by Compose
     * @param content - the Composable lambda that defines the composition content
     *
     * @return the instance of the [Composition]
     */
    @OptIn(ComposeWebInternalApi::class)
    @ComposeWebExperimentalTestsApi
    fun <TElement : Element> renderTestComposable(
        root: TElement,
        content: @Composable () -> Unit
    ): Composition {
        GlobalSnapshotManager.ensureStarted()

        val context = TestMonotonicClockImpl(
            onRecomposeComplete = this::onRecompositionComplete
        ) + JsMicrotasksDispatcher()

        val recomposer = Recomposer(context)
        val composition = ControlledComposition(
            applier = DomApplier(DomNodeWrapper(root)),
            parent = recomposer
        )
        composition.setContent @Composable {
            content()
        }

        CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        return composition
    }

    /**
     * @return a reference to the next child element of the root.
     * Subsequent calls will return next child reference every time.
     */
    fun nextChild() = childrenIterator.next() as HTMLElement
    fun <T> nextChild() = childrenIterator.next() as T

    /**
     * @return a reference to current child.
     * Calling this subsequently returns the same reference every time.
     */
    fun currentChild() = root.children[childrenIterator.previousIndex()] as HTMLElement

    /**
     * Suspends until [root] observes any change to its html.
     */
    suspend fun waitForChanges() {
        waitForChanges(root)
    }

    /**
     * Suspends until element with [elementId] observes any change to its html.
     */
    suspend fun waitForChanges(elementId: String) {
        waitForChanges(document.getElementById(elementId) as HTMLElement)
    }

    /**
     * Suspends until [element] observes any change to its html.
     */
    suspend fun waitForChanges(element: HTMLElement) {
        suspendCoroutine<Unit> { continuation ->
            val observer = MutationObserver { mutations, observer ->
                continuation.resume(Unit)
                observer.disconnect()
            }
            observer.observe(element, MutationObserverOptions)
        }
    }

    /**
     * Suspends until recomposition completes.
     */
    suspend fun waitForRecompositionComplete() {
        suspendCoroutine<Unit> { continuation ->
            waitForRecompositionCompleteContinuation = continuation
        }
    }
}

/**
 * Use this method to test compose-web components rendered using HTML.
 * Declare states and make assertions in [block].
 * Use [TestScope.composition] to define the code under test.
 *
 * For dynamic tests, use [TestScope.waitForRecompositionComplete]
 * after changing state's values and before making assertions.
 *
 * @see [TestScope.composition]
 * @see [TestScope.waitForRecompositionComplete]
 * @see [TestScope.waitForChanges].
 *
 * Test example:
 * ```
 * @Test
 * fun textChild() = runTest {
 *      var textState by mutableStateOf("inner text")
 *
 *      composition {
 *          Div {
 *              Text(textState)
 *          }
 *      }
 *      assertEquals("<div>inner text</div>", root.innerHTML)
 *
 *      textState = "new text"
 *      waitForRecompositionComplete()
 *
 *      assertEquals("<div>new text</div>", root.innerHTML)
 * }
 * ```
 */
@ComposeWebExperimentalTestsApi
fun runTest(block: suspend TestScope.() -> Unit): dynamic {
    val scope = TestScope()
    return scope.promise { block(scope) }
}

@ComposeWebExperimentalTestsApi
fun String.asHtmlElement() = document.createElement(this) as HTMLElement

private object MutationObserverOptions : MutationObserverInit {
    override var childList: Boolean? = true
    override var attributes: Boolean? = true
    override var characterData: Boolean? = true
    override var subtree: Boolean? = true
    override var attributeOldValue: Boolean? = true
}

@OptIn(ExperimentalTime::class)
private class TestMonotonicClockImpl(
    private val onRecomposeComplete: () -> Unit
) : MonotonicFrameClock {

    override suspend fun <R> withFrameNanos(
        onFrame: (Long) -> R
    ): R = suspendCoroutine { continuation ->
        window.requestAnimationFrame {
            val duration = it.toDuration(DurationUnit.MILLISECONDS)
            val result = onFrame(duration.inWholeNanoseconds)
            continuation.resume(result)
            onRecomposeComplete()
        }
    }
}
