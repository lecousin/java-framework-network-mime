package net.lecousin.framework.network.mime.header.parser;

import java.util.List;

import net.lecousin.framework.text.IString;

/** Abstract class for token sourrounding tokens. */
public abstract class SurroundedTokens implements Token {

	protected SurroundedTokens(char start, char end, List<Token> tokens) {
		this.startChar = start;
		this.endChar = end;
		this.tokens = tokens;
	}
	
	protected char startChar;
	protected char endChar;
	protected List<Token> tokens;
	
	public List<Token> getContent() {
		return tokens;
	}

	@Override
	public int textLength() {
		return Token.textLength(tokens) + 2;
	}
	
	@Override
	public void asText(IString s) {
		s.append(startChar);
		Token.asText(tokens, s);
		s.append(endChar);
	}
	
}
