package com.zero7goat.taz.core.transfer

import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All data here is synthetic. */
class LoginImportTest {

    private fun success(text: String) = LoginImportParser.parse(text) as ImportParseResult.Success

    @Test
    fun readsChromeCsv() {
        val result = success(
            "name,url,username,password,note\n" +
                "example.com,https://example.com/login,alice,\"p,a\"\"ss\",\"line1\nline2\"\n" +
                "shop.test,https://shop.test/,bob,secret,\n"
        )
        assertEquals("Chrome CSV", result.format)
        assertEquals(2, result.logins.size)
        val first = result.logins[0]
        assertEquals("example.com", first.name)
        assertEquals("alice", first.username)
        assertEquals("p,a\"ss", first.password)
        assertEquals("line1\nline2", first.notes)
    }

    @Test
    fun readsFirefoxCsvWithoutNamesAndKeepsPasswordSpaces() {
        val result = success(
            "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\"\r\n" +
                "\"https://www.example.org\",\"carol\",\" spaced \",,\"https://example.org\",\"{1}\"\r\n"
        )
        assertEquals("Firefox CSV", result.format)
        assertEquals("example.org", result.logins.single().name)
        assertEquals(" spaced ", result.logins.single().password)
    }

    @Test
    fun readsBitwardenCsvSkippingNonLogins() {
        val result = success(
            "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n" +
                "Work,1,login,Mail,,,0,https://mail.test,dave,pw1,JBSWY3DPEHPK3PXP\n" +
                ",,note,My note,text,,0,,,,\n"
        )
        assertEquals("Bitwarden CSV", result.format)
        assertEquals(1, result.skipped)
        val login = result.logins.single()
        assertEquals("Work", login.folder)
        assertTrue(login.favorite)
        assertEquals("JBSWY3DPEHPK3PXP", login.totp)
    }

    @Test
    fun readsKeePassGroupsAndOnePasswordTags() {
        val keepass = success(
            "\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\",\"TOTP\"\n" +
                "\"Root/Internet/Shopping\",\"Store\",\"erin\",\"pw\",\"https://store.test\",\"\",\"\"\n"
        )
        assertEquals("KeePass CSV", keepass.format)
        assertEquals("Shopping", keepass.logins.single().folder)

        val onePassword = success(
            "Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes\n" +
                "Bank,https://bank.test,frank,pw,,false,false,\"money; important\",\n"
        )
        assertEquals("1Password CSV", onePassword.format)
        assertEquals(listOf("money", "important"), onePassword.logins.single().tags)
        assertFalse(onePassword.logins.single().favorite)
    }

    @Test
    fun keepsEmailWhenThereIsAlsoAUsername() {
        val result = success(
            "type,name,url,email,username,password,note,totp,createTime,modifyTime,vault\n" +
                "login,Forum,https://forum.test,gina@example.test,gina,pw,,,1,1,Personal\n"
        )
        val login = result.logins.single()
        assertEquals("gina", login.username)
        assertEquals("gina@example.test", login.extraFields.single().value)
    }

    @Test
    fun semicolonSeparatedFilesWork() {
        val result = success("name;url;username;password\nSite;https://site.test;hank;pw\n")
        assertEquals("hank", result.logins.single().username)
    }

    @Test
    fun readsBitwardenJsonWithFoldersAndFields() {
        val json = """
            {"encrypted": false,
             "folders": [{"id": "f1", "name": "Personal"}],
             "items": [
               {"type": 1, "name": "Social", "notes": null, "favorite": true, "folderId": "f1",
                "fields": [{"name": "PIN", "value": "1234", "type": 1}, {"name": "Linked", "value": null, "type": 3}],
                "login": {"username": "ivy", "password": "pw", "totp": "otpauth://totp/S:ivy?secret=JBSWY3DPEHPK3PXP",
                          "uris": [{"uri": "https://social.test"}]}},
               {"type": 3, "name": "Card", "card": {}}
             ]}
        """.trimIndent()
        val result = success(json)
        assertEquals("Bitwarden JSON", result.format)
        assertEquals(1, result.skipped)
        val login = result.logins.single()
        assertEquals("Personal", login.folder)
        assertTrue(login.favorite)
        assertEquals("", login.notes)
        assertEquals("PIN", login.extraFields.single().label)
        assertTrue(login.extraFields.single().hidden)
    }

    @Test
    fun explainsWhatIsWrongWithUnsupportedFiles() {
        assertTrue(LoginImportParser.parse("") is ImportParseResult.Failure)
        assertTrue(LoginImportParser.parse("""{"encrypted": true, "items": []}""") is ImportParseResult.Failure)
        assertTrue(LoginImportParser.parse("a,b,c\n1,2,3\n") is ImportParseResult.Failure) // no password column
        assertTrue(LoginImportParser.parse("name,password\n,\n") is ImportParseResult.Failure) // nothing usable
    }

    @Test
    fun duplicateKeyIgnoresCaseOfSiteAndUsernameButNotPassword() {
        val a = LoginImportParser.duplicateKey("Example.com", "x", "Alice", "pw")
        val b = LoginImportParser.duplicateKey("example.com", "y", "alice", "pw")
        val c = LoginImportParser.duplicateKey("example.com", "y", "alice", "PW")
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun csvExportRoundTripsThroughTheImporter() {
        val csv = LoginCsvExport.write(
            listOf(
                CsvLogin(
                    folder = "Work", favorite = true, name = "Mail, \"main\"", notes = "two\nlines",
                    url = "https://mail.test", username = "jo", password = " =SUM(A1) ",
                    fields = listOf(
                        CustomField("1", "Two-factor code", FieldType.TOTP, "JBSWY3DPEHPK3PXP"),
                        CustomField("2", "Recovery", FieldType.SECRET, "abc")
                    )
                )
            )
        )
        val back = success(csv)
        assertEquals("Bitwarden CSV", back.format)
        val login = back.logins.single()
        assertEquals("Mail, \"main\"", login.name)
        assertEquals(" =SUM(A1) ", login.password) // passwords are written exactly
        assertEquals("two\nlines", login.notes)
        assertEquals("JBSWY3DPEHPK3PXP", login.totp)
        assertEquals("Work", login.folder)
        assertTrue(login.favorite)
        assertTrue(csv.contains("Recovery: abc"))
    }

    @Test
    fun toStringNeverShowsSecrets() {
        val login = ImportedLogin("Site", "", "user", "hunter2")
        assertFalse(login.toString().contains("hunter2"))
    }
}
