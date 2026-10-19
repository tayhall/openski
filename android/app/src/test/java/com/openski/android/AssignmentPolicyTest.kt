package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class AssignmentPolicyTest {
    private val saved = mapOf("L" to "left-sensor", "R" to "right-sensor")

    @Test fun savedAssignmentPreventsDuplicateBootIdentityBeforeConnection() {
        assertFalse(AssignmentPolicy.canAssign("R", "left-sensor", saved, captureProtected = false))
        assertFalse(AssignmentPolicy.canAssign("L", "right-sensor", saved, captureProtected = false))
        assertTrue(AssignmentPolicy.canAssign("L", "replacement", saved, captureProtected = false))
    }

    @Test fun retainedDataAllowsSameOwnerReconnectButRefusesReplacementOrCrossAssignment() {
        assertTrue(AssignmentPolicy.canAssign("R", "right-sensor", saved, captureProtected = true))
        assertFalse(AssignmentPolicy.canAssign("R", "replacement", saved, captureProtected = true))
        assertFalse(AssignmentPolicy.canAssign("R", "left-sensor", saved, captureProtected = true))
        assertFalse(AssignmentPolicy.canAssign("L", "new", emptyMap(), captureProtected = true))
    }

    @Test fun newAssignmentsRequireARealBootSlot() {
        assertTrue(AssignmentPolicy.canAssign("L", "new", emptyMap(), captureProtected = false))
        assertFalse(AssignmentPolicy.canAssign("other", "new", emptyMap(), captureProtected = false))
    }
}
