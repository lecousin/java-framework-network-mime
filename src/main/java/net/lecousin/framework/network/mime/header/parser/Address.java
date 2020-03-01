package net.lecousin.framework.network.mime.header.parser;

import java.util.List;

import net.lecousin.framework.text.IString;

/** Address token. */
public class Address implements Token {

	/** Constructor. */
	public Address(List<Token> tokens) {
		this.tokens = tokens;
	}
	
	private List<Token> tokens;
	
	public List<Token> getContent() {
		return tokens;
	}

	@Override
	public int textLength() {
		return Token.textLength(tokens) + 2;
	}
	
	@Override
	public void asText(IString s) {
		s.append('<');
		Token.asText(tokens, s);
		s.append('>');
	}
	
}
