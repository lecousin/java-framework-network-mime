package net.lecousin.framework.network.mime.header;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.network.mime.header.parser.Address;
import net.lecousin.framework.network.mime.header.parser.Space;
import net.lecousin.framework.network.mime.header.parser.Token;
import net.lecousin.framework.network.mime.header.parser.Word;

public class InternetAddressHeaderValue implements HeaderValueFormat {

	public InternetAddressHeaderValue() {
	}

	public InternetAddressHeaderValue(String displayName, String address) {
		this.displayName = displayName;
		this.address = address;
	}
	
	protected String displayName;
	protected String address;
	
	public String getDisplayName() {
		return displayName;
	}
	
	public void setDisplayName(String name) {
		displayName = name;
	}
	
	public String getAddress() {
		return address;
	}
	
	public void setAddress(String address) {
		this.address = address;
	}
	
	@Override
	public void parseTokens(List<Token> tokens) {
		Token.removeComments(tokens);
		int i = 0;
		while (i < tokens.size() && !(tokens.get(i) instanceof Address))
			i++;
		if (i == tokens.size()) {
			// no Address token => full text is considered as address
			displayName = null;
			address = Token.asText(tokens);
			return;
		}
		List<Token> addrTokens = ((Address)tokens.get(i)).getContent();
		Token.trim(addrTokens);
		address = Token.asText(addrTokens);
		while (tokens.size() > i)
			tokens.remove(i);
		Token.trim(tokens);
		if (!tokens.isEmpty())
			displayName = Token.asText(tokens);
		else
			displayName = null;
	}

	@Override
	public List<Token> generateTokens() {
		List<Token> tokens = new LinkedList<>();
		if (displayName != null) {
			tokens.add(new Word(displayName));
			tokens.add(new Space());
		}
		tokens.add(new Address(Collections.singletonList(new Word(address))));
		return tokens;
	}

}
