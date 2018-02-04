package net.lecousin.framework.network.mime.header.parser;

import java.util.List;

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
	public String asText() {
		return "[" + Token.asText(tokens) + "]";
	}
	
}
