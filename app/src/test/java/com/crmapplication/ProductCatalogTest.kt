package com.crmapplication

import com.crmapplication.LeadDetailVM.repository.DEFAULT_PRODUCTS
import com.crmapplication.LeadDetailVM.repository.Lead
import com.crmapplication.LeadDetailVM.repository.sanitizeConfigList
import com.crmapplication.viewModel.LeadsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the product catalog served by `GET /api/config`, which drives the Add Lead dropdown.
 */
class ProductCatalogTest {

    @Test
    fun `server ordering is preserved, not sorted`() {
        // The admin controls the order in the config document, so alphabetising it would override
        // a deliberate choice (e.g. best-sellers first).
        val fromServer = listOf("Spiti Package", "Adventure Activities", "Kerala Trip")
        assertEquals(fromServer, sanitizeConfigList(fromServer))
    }

    @Test
    fun `blank and whitespace-only entries are dropped and names trimmed`() {
        assertEquals(
            listOf("Ladakh Package", "Others"),
            sanitizeConfigList(listOf("  Ladakh Package ", "", "   ", "Others")),
        )
    }

    @Test
    fun `duplicates are removed case-insensitively, keeping the first spelling`() {
        // The backend rejects duplicates on write, but an older document may still hold them.
        assertEquals(
            listOf("Kerala Trip", "Others"),
            sanitizeConfigList(listOf("Kerala Trip", "KERALA TRIP", "kerala trip", "Others")),
        )
    }

    @Test
    fun `a missing or empty products field yields an empty list`() {
        // The repository treats empty as "nothing to apply" and keeps the previous cache, so the
        // dropdown never blanks out.
        assertTrue(sanitizeConfigList(null).isEmpty())
        assertTrue(sanitizeConfigList(emptyList()).isEmpty())
    }

    @Test
    fun `the offline fallback matches the documented server default`() {
        assertEquals(
            listOf(
                "Meghalaya Package",
                "Hampta Pass Trek",
                "Rishikesh Activities",
                "Spiti Package",
                "Ladakh Package",
                "Kerala Trip",
                "Adventure Activities",
                "Others",
            ),
            DEFAULT_PRODUCTS,
        )
    }

    @Test
    fun `the fallback itself survives sanitizing unchanged`() {
        assertEquals(DEFAULT_PRODUCTS, sanitizeConfigList(DEFAULT_PRODUCTS))
    }

    // region list filter options (LeadsUiState.availableProducts)

    private fun lead(name: String, product: String?) = Lead(
        id = name,
        name = name,
        phone = "9876543210",
        product = product,
        createdAt = 0L,
        dueDate = null,
    )

    /**
     * The reason this property isn't just derived from the leads on hand: a product added on the
     * backend has to be filterable before any lead carries it, the same way it's immediately
     * pickable in the Add Lead dropdown.
     */
    @Test
    fun `a config product with no matching leads is still offered`() {
        val state = LeadsUiState(
            leads = listOf(lead("Asha", "Kerala Trip")),
            products = listOf("Kerala Trip", "Bhutan Package"),
        )
        assertEquals(listOf("Kerala Trip", "Bhutan Package"), state.availableProducts)
    }

    @Test
    fun `config keeps server order and lead-only products follow, sorted`() {
        val state = LeadsUiState(
            leads = listOf(
                lead("Asha", "Zanskar Trek"),
                lead("Bala", "Andaman Package"),
                lead("Chitra", "Spiti Package"),
            ),
            products = listOf("Spiti Package", "Kerala Trip"),
        )
        assertEquals(
            listOf("Spiti Package", "Kerala Trip", "Andaman Package", "Zanskar Trek"),
            state.availableProducts,
        )
    }

    /** A product retired from config stays filterable while leads still carry it. */
    @Test
    fun `a lead product missing from config is kept, not dropped`() {
        val state = LeadsUiState(
            leads = listOf(lead("Asha", "Discontinued Package")),
            products = listOf("Kerala Trip"),
        )
        assertTrue(state.availableProducts.contains("Discontinued Package"))
    }

    @Test
    fun `a lead product already in config is not duplicated`() {
        val state = LeadsUiState(
            leads = listOf(lead("Asha", "kerala trip"), lead("Bala", "KERALA TRIP")),
            products = listOf("Kerala Trip"),
        )
        assertEquals(listOf("Kerala Trip"), state.availableProducts)
    }

    @Test
    fun `blank and absent lead products are ignored`() {
        val state = LeadsUiState(
            leads = listOf(lead("Asha", null), lead("Bala", "   ")),
            products = listOf("Kerala Trip"),
        )
        assertEquals(listOf("Kerala Trip"), state.availableProducts)
    }

    /**
     * Same guarantee the status chips give: an active filter must always have a visible control, or
     * the agent is left filtering by something they can't see to clear.
     */
    @Test
    fun `an active product absent from config and leads is kept last`() {
        val state = LeadsUiState(
            leads = listOf(lead("Asha", "Kerala Trip")),
            products = listOf("Kerala Trip"),
            activeProduct = "Gone Package",
        )
        assertEquals(listOf("Kerala Trip", "Gone Package"), state.availableProducts)
    }

    @Test
    fun `an active product already offered is not repeated`() {
        val state = LeadsUiState(
            leads = emptyList(),
            products = listOf("Kerala Trip", "Spiti Package"),
            activeProduct = "Spiti Package",
        )
        assertEquals(listOf("Kerala Trip", "Spiti Package"), state.availableProducts)
    }

    // endregion
}
