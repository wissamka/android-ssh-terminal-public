package com.termux.terminal;

/**
 * "\033P" is a device control string.
 */
public class DeviceControlStringTest extends TerminalTestCase {

	private static String hexEncode(String s) {
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < s.length(); i++)
			result.append(String.format("%02X", (int) s.charAt(i)));
		return result.toString();
	}

	private void assertCapabilityResponse(String cap, String expectedResponse) {
		String input = "\033P+q" + hexEncode(cap) + "\033\\";
		assertEnteringStringGivesResponse(input, "\033P1+r" + hexEncode(cap) + "=" + hexEncode(expectedResponse) + "\033\\");
	}

	public void testReportColorsAndName() {
		// Request Termcap/Terminfo String. The string following the "q" is a list of names encoded in
		// hexadecimal (2 digits per character) separated by ; which correspond to termcap or terminfo key
		// names.
		// Two special features are also recognized, which are not key names: Co for termcap colors (or colors
		// for terminfo colors), and TN for termcap name (or name for terminfo name).
		// xterm responds with DCS 1 + r P t ST for valid requests, adding to P t an = , and the value of the
		// corresponding string that xterm would send, or DCS 0 + r P t ST for invalid requests. The strings are
		// encoded in hexadecimal (2 digits per character).
		withTerminalSized(3, 3).enterString("A");
		assertCapabilityResponse("Co", "256");
		assertCapabilityResponse("colors", "256");
		assertCapabilityResponse("TN", "xterm");
		assertCapabilityResponse("name", "xterm");
		enterString("B").assertLinesAre("AB ", "   ", "   ");
	}

	public void testReportKeys() {
		withTerminalSized(3, 3);
		assertCapabilityResponse("kB", "\033[Z");
	}

	public void testMalformedCapabilitiesNeverBecomeShellInput() {
		withTerminalSized(20, 3);
		// Even-length shell syntax previously survived failed hex decoding and was echoed
		// verbatim into the shell's input. Assert at the actual terminal output boundary.
		for (String part : new String[] {
			"$(id)###", "`id`", "436f$(id)###", "43zz6f", "", "4", "gg",
			"+1", "-1", "\uff14\uff13\uff16\uff46"
		}) {
			assertEnteringStringGivesResponse("\033P+q" + part + "\033\\", "");
		}
	}

	public void testControlBytesInCapabilityNamesNeverBecomeShellInput() {
		withTerminalSized(20, 3);
		// These C0 bytes reach the DCS collector (including Ctrl-U, a shell line kill).
		// A valid capability around a malformed pair must not salvage that item.
		for (char control : new char[] { 1, 2, 3, 4, 5, 6, 16, 17, 18, 19, 20, 21, 22, 23, 25, 28, 29, 30, 31, 127 }) {
			assertEnteringStringGivesResponse("\033P+q43" + control + "x6f\033\\", "");
		}
	}

	public void testMixedCapabilityListRejectsOnlyMalformedItems() {
		withTerminalSized(20, 3);
		String request = "\033P+q$(id)###;436f;;43zz6f;43\025x6f;544e;7878;4;\033\\";
		// Valid known capabilities still work; an unknown *validly encoded* name gets
		// a negative response containing hex only, never decoded or malformed text.
		assertEnteringStringGivesResponse(request,
			"\033P1+r436f=323536\033\\" +
			"\033P1+r544e=787465726D\033\\" +
			"\033P0+r7878\033\\");
		// Rejection must not leave the parser in a DCS state or poison later queries.
		assertCapabilityResponse("TN", "xterm");
	}

	public void testEncodedShellSyntaxAndControlsRemainHexInNegativeResponses() {
		withTerminalSized(20, 3);
		String encoded = hexEncode("\025$(id)");
		assertEnteringStringGivesResponse("\033P+q" + encoded + "\033\\",
			"\033P0+r" + encoded + "\033\\");
	}

	public void testReallyLongDeviceControlString() {
		withTerminalSized(3, 3).enterString("\033P");
		for (int i = 0; i < 10000; i++) {
			enterString("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		}
		// The terminal should ignore the overlong DCS sequence and continue printing "aaa." and fill at least the first two lines with
		// them:
		assertLineIs(0, "aaa");
		assertLineIs(1, "aaa");
	}

}
