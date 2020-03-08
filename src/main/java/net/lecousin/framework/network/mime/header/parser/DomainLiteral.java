package net.lecousin.framework.network.mime.header.parser;

import java.util.List;

/** Domain literal token. */
public class DomainLiteral extends SurroundedTokens {

	/** Constructor. */
	public DomainLiteral(List<Token> tokens) {
		super('[', ']', tokens);
	}
	
}
