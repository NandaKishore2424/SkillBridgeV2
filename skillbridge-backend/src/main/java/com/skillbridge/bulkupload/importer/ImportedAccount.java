package com.skillbridge.bulkupload.importer;

import com.skillbridge.auth.invitation.IssuedInvitation;

/** A row that committed: its result row, and the invitation still to be mailed. */
public record ImportedAccount(Long resultId, IssuedInvitation invitation) {
}
