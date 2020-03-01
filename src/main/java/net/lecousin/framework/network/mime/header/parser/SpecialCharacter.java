package net.lecousin.framework.network.mime.header.parser;

import net.lecousin.framework.text.IString;

/** Special character token. */
public class SpecialCharacter implements Token {

	/** Constructor. */
	public SpecialCharacter(char c) {
		this.c = c;
	}
	
	private char c;
	
	public char getChar() { return c; }

	@Override
	public int textLength() {
		return 1;
	}
	
	@Override
	public void asText(IString s) {
		s.append(c);
	}
	
}
