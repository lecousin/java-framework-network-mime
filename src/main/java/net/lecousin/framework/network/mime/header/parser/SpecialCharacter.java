package net.lecousin.framework.network.mime.header.parser;

public class SpecialCharacter implements Token {

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
