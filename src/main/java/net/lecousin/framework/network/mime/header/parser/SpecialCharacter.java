package net.lecousin.framework.network.mime.header.parser;

/** Special character token. */
public class SpecialCharacter implements Token {

	/** Constructor. */
	public SpecialCharacter(char c) {
		this.c = c;
	}
	
	private char c;
	
	public char getChar() { return c; }
	
	@Override
	public String asText() {
		return new String(new char[] { c });
	}
	
}
