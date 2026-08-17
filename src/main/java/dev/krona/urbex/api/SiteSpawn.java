package dev.krona.urbex.api;

/**
 * Where inside a site a claimed world spawn should land.
 *
 * <p><strong>Experimental.</strong> See {@link UrbexApi} for what that means.</p>
 */
public enum SiteSpawn {

    /**
     * On a street or open lot - a site chunk the plan gave no building.
     *
     * <p>The safe choice, and the one to reach for first. A street is clear by construction and lit
     * by whatever the preset's lighting density puts there, so the player opens their eyes looking
     * at the place rather than at the inside of a wall.</p>
     */
    STREET,

    /**
     * Inside a building, on its ground floor.
     *
     * <p>The more dramatic opening, and the riskier one: a ground floor is furnished, so the search
     * has more blocks to reject before it finds somewhere to stand, and a site whose buildings are
     * dense throughout may push the search further out than {@link #STREET} would.</p>
     */
    BUILDING
}
