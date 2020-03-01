package net.lecousin.framework.network.mime.header.parser;

import java.util.List;

import net.lecousin.framework.text.IString;

/** Domain literal token. */
public class DomainLiteral implements Token {

	/** Constructor. */
	public DomainLiteral(List<Token> tokens) {
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
		s.append('[');
		Token.asText(tokens, s);
		s.append(']');
	}
	
}
