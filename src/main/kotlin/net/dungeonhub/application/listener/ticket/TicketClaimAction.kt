package net.dungeonhub.application.listener.ticket

import net.dungeonhub.enums.TicketState

internal enum class TicketClaimAction {
    Claim,
    Unclaim,
    NotATicket,
    NotClaimable,
    Deleted,
    NotOpen,
}

internal fun resolveTicketClaimAction(
    ticketExists: Boolean,
    claimable: Boolean,
    state: TicketState?,
    hasClaimer: Boolean,
): TicketClaimAction = when {
    !ticketExists -> TicketClaimAction.NotATicket
    !claimable -> TicketClaimAction.NotClaimable
    state == TicketState.Deleted -> TicketClaimAction.Deleted
    state != TicketState.Open -> TicketClaimAction.NotOpen
    hasClaimer -> TicketClaimAction.Unclaim
    else -> TicketClaimAction.Claim
}
