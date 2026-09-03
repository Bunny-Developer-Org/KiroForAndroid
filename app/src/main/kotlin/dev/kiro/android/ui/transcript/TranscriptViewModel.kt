package dev.kiro.android.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.model.ModelSelection
import dev.kiro.core.model.PermissionRequest
import dev.kiro.core.model.UserInputRequest
import dev.kiro.core.session.CloudSessionGateway
import dev.kiro.core.session.PromptBlock
import dev.kiro.core.session.TranscriptReducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Owns one session's transcript.
 *
 * The coalescing tick below is the reason this class exists rather than the
 * screen collecting the gateway directly. ADR-003 §3 requires it: chunks arrive
 * many times per second, and recomposing per chunk is the specific mistake that
 * makes a transcript stutter. Updates are queued as they arrive and drained on a
 * fixed tick, so the UI recomposes at most [TICK_MILLIS] apart no matter how fast
 * the agent talks.
 */
class TranscriptViewModel(
    private val gateway: CloudSessionGateway,
    private val sessionId: String,
) : ViewModel() {

    private val reducer = TranscriptReducer()
    private val inbox = ConcurrentLinkedQueue<SessionUpdate>()

    private val _state = MutableStateFlow(TranscriptReducer.State())
    val state: StateFlow<TranscriptReducer.State> = _state.asStateFlow()

    private val _permission = MutableStateFlow<PermissionRequest?>(null)

    /**
     * The approval currently owed an answer.
     *
     * Separate from the reducer's own `pendingApproval` because the two arrive by
     * different routes: a `pending_interaction` update comes in-stream, while a
     * `session/request_permission` is a server-initiated request that may have
     * been raised while nothing was attached. Either can be first.
     */
    val permission: StateFlow<PermissionRequest?> = _permission.asStateFlow()

    private val _userInput = MutableStateFlow<UserInputRequest?>(null)

    /** The free-text question currently owed an answer. See [permission]'s doc. */
    val userInput: StateFlow<UserInputRequest?> = _userInput.asStateFlow()

    // Seeded synchronously rather than started at Unknown: a screen reopened on a
    // session the gateway already has a snapshot for must not flash "not reported
    // yet" for one frame before the flow's first emission arrives.
    private val _models = MutableStateFlow(gateway.modelsFor(sessionId))

    /**
     * Which model this session is using, as last reported *by the agent*.
     *
     * Written only from [CloudSessionGateway.models]. [setModel] deliberately does
     * not touch it: PROTOCOL-FINDINGS §4d has the agent confirm the switch in its
     * `session/set_config_option` response, and a client that painted the request
     * instead would show a model the session is not actually running.
     */
    val models: StateFlow<ModelSelection> = _models.asStateFlow()

    private val _modelChange = MutableStateFlow<ModelChange>(ModelChange.Idle)

    /** Progress of an operator-requested switch, so a refusal can be named. */
    val modelChange: StateFlow<ModelChange> = _modelChange.asStateFlow()

    init {
        viewModelScope.launch {
            gateway.updates.collect { update ->
                if (update.sessionId == sessionId) inbox.add(update)
            }
        }

        viewModelScope.launch {
            gateway.permissionRequests.collect { request ->
                if (request.sessionId == sessionId) _permission.value = request
            }
        }

        viewModelScope.launch {
            gateway.userInputRequests.collect { request ->
                if (request.sessionId == sessionId) _userInput.value = request
            }
        }

        viewModelScope.launch {
            gateway.models.collect { state -> _models.value = state.forSession(sessionId) }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                drain()
            }
        }
    }

    /**
     * Applies everything queued since the last tick in one state write.
     *
     * One `_state.value =` per tick rather than per update is the point: a burst
     * of forty chunks becomes a single recomposition.
     */
    private fun drain() {
        if (inbox.isEmpty()) return
        var next = _state.value
        while (true) {
            val update = inbox.poll() ?: break
            next = reducer.reduce(next, update)
            // An approval resolved by anyone -- including another client -- clears
            // the card here too, so the phone does not keep asking for a decision
            // that has already been made.
            if (update is SessionUpdate.InteractionResolved &&
                _permission.value?.toolCallId == update.toolCallId
            ) {
                _permission.value = null
            }
            // Same story for a userInput question -- it can be answered from
            // another client too, correlated the same way.
            if (update is SessionUpdate.InteractionResolved &&
                _userInput.value?.toolCallId == update.toolCallId
            ) {
                _userInput.value = null
            }
        }
        _state.value = next
    }

    /**
     * Sends a turn made of optional text plus an optional attached image.
     *
     * At least one of [text]/[image] should be non-empty for this to be a
     * meaningful turn, but that's the composer's call to enforce (it disables
     * the send button); this just builds whichever blocks are present.
     */
    fun send(text: String, image: PromptBlock.Image? = null) {
        val blocks = listOfNotNull(
            PromptBlock.Text(text).takeIf { text.isNotBlank() },
            image,
        )
        if (blocks.isEmpty()) return
        viewModelScope.launch {
            gateway.prompt(sessionId, blocks)
        }
    }

    fun cancel() {
        viewModelScope.launch { gateway.cancel(sessionId) }
    }

    fun respondToPermission(optionId: String) {
        val request = _permission.value ?: return
        // Cleared optimistically: the user has decided, and leaving the card up
        // until the round trip completes invites a double tap on the one surface
        // where a mis-tap cannot be undone.
        _permission.value = null
        viewModelScope.launch {
            gateway.respondToPermission(sessionId, request.toolCallId, optionId)
        }
    }

    /**
     * Answers -- or dismisses -- a pending `_kiro/userInput` question.
     *
     * [answer] is null for a dismiss and a non-blank string for a real answer;
     * mirrors [respondToPermission]'s optimistic clear-before-round-trip so the
     * card cannot be actioned twice.
     */
    fun respondToUserInput(answer: String?) {
        val request = _userInput.value ?: return
        _userInput.value = null
        viewModelScope.launch {
            gateway.respondToUserInput(sessionId, request.toolCallId, answer)
        }
    }

    /**
     * Asks the agent to switch models from the next message onwards.
     *
     * The switch is *not* applied locally on success -- see [models]. All this
     * does on success is drop back to [ModelChange.Idle] and let the agent's own
     * report repaint the bar.
     *
     * A refusal becomes [ModelChange.Failed] rather than a thrown exception or a
     * cleared screen: the session is still usable on its existing model, and
     * losing the transcript over a rejected preference would cost the user far
     * more than the switch was worth.
     */
    fun setModel(modelId: String) {
        if (_modelChange.value is ModelChange.InFlight) return
        _modelChange.value = ModelChange.InFlight(modelId)
        viewModelScope.launch {
            try {
                gateway.setModel(sessionId, modelId)
                _modelChange.value = ModelChange.Idle
            } catch (e: CancellationException) {
                // Scope teardown, not a refusal: rethrow so cancellation stays
                // cooperative instead of being reported to the user as an error.
                throw e
            } catch (e: Throwable) {
                _modelChange.value = ModelChange.Failed(modelId, e.message ?: DEFAULT_REFUSAL)
            }
        }
    }

    /** Dismisses a [ModelChange.Failed] banner the user has read. */
    fun dismissModelChangeError() {
        if (_modelChange.value is ModelChange.Failed) _modelChange.value = ModelChange.Idle
    }

    companion object {
        /**
         * ADR-003 §3's window. Fast enough to read as live, slow enough that a
         * chatty turn cannot drive the frame rate.
         */
        const val TICK_MILLIS = 80L

        /** Used when the agent refuses a switch without saying why. */
        const val DEFAULT_REFUSAL = "The agent refused the switch."
    }
}

/**
 * Progress of a model switch the operator asked for.
 *
 * Separate from [ModelSelection] because the two answer different questions:
 * that one is what the agent says is true, this one is what this client has
 * asked for and how it went. Conflating them is how a UI ends up showing a
 * model the session is not using.
 */
sealed interface ModelChange {
    data object Idle : ModelChange

    data class InFlight(val modelId: String) : ModelChange

    /** The agent said no. [message] is its own words where it gave any. */
    data class Failed(val modelId: String, val message: String) : ModelChange
}
