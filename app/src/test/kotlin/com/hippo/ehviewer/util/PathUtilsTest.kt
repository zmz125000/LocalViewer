package com.hippo.ehviewer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathUtilsTest {
    @Test
    fun nestedSafFoldersResolveToTheirOwnDocumentInsteadOfThePermissionRoot() {
        for (folder in listOf("siblings", "another sibling", ".hide", "siblings/nested")) {
            val current = "primary:folder/$folder"
            assertEquals(
                current,
                documentIdForDisplay(listOf("tree", "primary:folder", "document", current)),
            )
        }
    }

    @Test
    fun removableVolumeDocumentsKeepTheirCurrentFolder() {
        val document = "8254-36A8:folder/siblings"
        assertEquals(document, documentIdForDisplay(listOf("tree", "8254-36A8:folder", "document", document)))
    }

    @Test
    fun treeRootsAndStandaloneDocumentsStillResolve() {
        assertEquals("primary:folder", documentIdForDisplay(listOf("tree", "primary:folder")))
        assertEquals(
            "primary:folder",
            documentIdForDisplay(listOf("tree", "primary:folder", "document", "primary:folder")),
        )
        assertEquals("primary:", documentIdForDisplay(listOf("tree", "primary:")))
        assertEquals(
            "primary:folder/siblings",
            documentIdForDisplay(listOf("document", "primary:folder/siblings")),
        )
    }

    @Test
    fun invalidDocumentUrisDoNotResolveToTheTreeRoot() {
        assertNull(documentIdForDisplay(listOf("tree", "primary:folder", "document")))
        assertNull(documentIdForDisplay(listOf("document")))
        assertNull(documentIdForDisplay(emptyList()))
        assertNull(documentIdForDisplay(listOf("other", "primary:folder")))
    }
}
