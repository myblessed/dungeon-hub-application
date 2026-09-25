package net.dungeonhub.application.listener.ticket

import net.dungeonhub.enums.TicketState
import kotlin.test.Test
import kotlin.test.assertEquals

class TicketClaimActionTest {
    @Test
    fun `unclaimed open ticket can be claimed`() {
        assertEquals(
            TicketClaimAction.Claim,
            resolveTicketClaimAction(
                ticketExists = true,
                claimable = true,
                state = TicketState.Open,
                hasClaimer = false,
            ),
        )
    }

    @Test
    fun `claimed open ticket can be unclaimed`() {
        assertEquals(
            TicketClaimAction.Unclaim,
            resolveTicketClaimAction(
                ticketExists = true,
                claimable = true,
                state = TicketState.Open,
                hasClaimer = true,
            ),
        )
    }

    @Test
    fun `non-ticket channel is rejected`() {
        assertEquals(
            TicketClaimAction.NotATicket,
            resolveTicketClaimAction(
                ticketExists = false,
                claimable = false,
                state = null,
                hasClaimer = false,
            ),
        )
    }

    @Test
    fun `ticket from non-claimable panel is rejected`() {
        assertEquals(
            TicketClaimAction.NotClaimable,
            resolveTicketClaimAction(
                ticketExists = true,
                claimable = false,
                state = TicketState.Open,
                hasClaimer = false,
            ),
        )
    }

    @Test
    fun `deleted ticket is rejected`() {
        assertEquals(
            TicketClaimAction.Deleted,
            resolveTicketClaimAction(
                ticketExists = true,
                claimable = true,
                state = TicketState.Deleted,
                hasClaimer = false,
            ),
        )
    }

    @Test
    fun `creating ticket is rejected`() {
        assertEquals(
            TicketClaimAction.NotOpen,
            resolveTicketClaimAction(
                ticketExists = true,
                claimable = true,
                state = TicketState.Creating,
                hasClaimer = false,
            ),
        )
    }

    @Test
    fun `closed ticket is rejected`() {
        assertEquals(
            TicketClaimAction.NotOpen,
            resolveTicketClaimAction(
                ticketExists = true,
                claimable = true,
                state = TicketState.Closed,
                hasClaimer = false,
            ),
        )
    }
}
