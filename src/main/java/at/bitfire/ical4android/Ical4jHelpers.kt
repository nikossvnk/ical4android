/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.ical4android

import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.ComponentContainer
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.PropertyContainer

/*
 * Since ical4j 4, the lists returned by getProperties()/getParameters()/getComponents()
 * are not meant to be modified. Instead, the containers provide fluent add() methods with a
 * generic return type, which can't be inferred by Kotlin when the result is not used.
 * These operators provide the "+=" syntax that was used with ical4j 3.
 */

/**
 * Adds a property to a component or calendar (like `vEvent += Summary("Test")`).
 */
operator fun PropertyContainer.plusAssign(property: Property) {
    add<PropertyContainer>(property)
}

/**
 * Adds a parameter to a property (like `attendee += Rsvp.TRUE`).
 */
operator fun Property.plusAssign(parameter: Parameter) {
    add<Property>(parameter)
}

/**
 * Adds a sub-component to a component or calendar (like `vEvent += vAlarm` or `calendar += vEvent`).
 */
operator fun <C: Component> ComponentContainer<C>.plusAssign(component: C) {
    add<ComponentContainer<C>>(component)
}
