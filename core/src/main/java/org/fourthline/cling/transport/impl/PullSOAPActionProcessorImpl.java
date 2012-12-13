/*
 * Copyright (C) 2012 4th Line GmbH, Switzerland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.fourthline.cling.transport.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.control.ActionRequestMessage;
import org.fourthline.cling.model.message.control.ActionResponseMessage;
import org.fourthline.cling.model.meta.ActionArgument;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.transport.spi.SOAPActionProcessor;
import org.fourthline.cling.model.UnsupportedDataException;
import org.seamless.xml.XmlPullParserUtils;
import org.xmlpull.v1.XmlPullParser;

import javax.enterprise.inject.Alternative;

/**
 * Implementation based on the <em>Xml Pull Parser</em> XML processing API.
 * <p>
 * This processor is more lenient with parsing, looking only for the required XML tags.
 * </p>
 * <p>
 * To use this parser you need to install an implementation of the
 * <a href="http://www.xmlpull.org/impls.shtml">XMLPull API</a>.
 * </p>
 *
 * @author Michael Pujos
 */
@Alternative
public class PullSOAPActionProcessorImpl extends SOAPActionProcessorImpl {

    protected static Logger log = Logger.getLogger(SOAPActionProcessor.class.getName());

    public void readBody(ActionRequestMessage requestMessage, ActionInvocation actionInvocation) throws UnsupportedDataException {
        String body = getMessageBody(requestMessage);
        try {
            XmlPullParser xpp = XmlPullParserUtils.createParser(body);
            readBodyRequest(xpp, requestMessage, actionInvocation);
        } catch (Exception ex) {
            throw new UnsupportedDataException("Can't transform message payload: " + ex, ex, body);
        }
    }

    public void readBody(ActionResponseMessage responseMsg, ActionInvocation actionInvocation) throws UnsupportedDataException {
        String body = getMessageBody(responseMsg);
        try {
            XmlPullParser xpp = XmlPullParserUtils.createParser(body);
            readBodyElement(xpp);
            readBodyResponse(xpp, actionInvocation);
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new UnsupportedDataException("Can't transform message payload: " + ex, ex, body);
        }
    }

    protected void readBodyElement(XmlPullParser xpp) throws Exception {
        XmlPullParserUtils.searchTag(xpp, "Body");
    }

    protected void readBodyRequest(XmlPullParser xpp, ActionRequestMessage requestMessage, ActionInvocation actionInvocation) throws Exception {
        XmlPullParserUtils.searchTag(xpp, actionInvocation.getAction().getName());
        readActionInputArguments(xpp, actionInvocation);
    }

    protected void readBodyResponse(XmlPullParser xpp, ActionInvocation actionInvocation) throws Exception {
        // We're in the "Body" tag
        int event;
        do {
            event = xpp.next();
            if (event == XmlPullParser.START_TAG) {
                if (xpp.getName().equals("Fault")) {
                    ActionException e = readFaultElement(xpp);
                    actionInvocation.setFailure(e);
                    return;
                } else if (xpp.getName().equals(actionInvocation.getAction().getName() + "Response")) {
                    readActionOutputArguments(xpp, actionInvocation);
                    return;
                }
            }

        }
        while (event != XmlPullParser.END_DOCUMENT && (event != XmlPullParser.END_TAG || !xpp.getName().equals("Body")));
    }

    protected void readActionInputArguments(XmlPullParser xpp, ActionInvocation actionInvocation) throws Exception {
        actionInvocation.setInput(readArgumentValues(xpp, actionInvocation.getAction().getInputArguments()));
    }

    protected void readActionOutputArguments(XmlPullParser xpp, ActionInvocation actionInvocation) throws Exception {
        actionInvocation.setOutput(readArgumentValues(xpp, actionInvocation.getAction().getOutputArguments()));
    }

    protected Map<String, String> getMatchingNodes(XmlPullParser xpp, ActionArgument[] args) throws Exception {

        // This is a case-insensitive search!
        List<String> names = new ArrayList<String>();
        for (ActionArgument argument : args) {
            names.add(argument.getName().toUpperCase());
            for (String alias : Arrays.asList(argument.getAliases())) {
                names.add(alias.toUpperCase());
            }
        }

        Map<String, String> matches = new HashMap<String, String>();

        String enclosingTag = xpp.getName();

        int event;
        do {
            event = xpp.next();
            if(event == XmlPullParser.START_TAG && names.contains(xpp.getName().toUpperCase())) {
                matches.put(xpp.getName(), xpp.nextText());
            }

        }
        while (event != XmlPullParser.END_DOCUMENT && (event != XmlPullParser.END_TAG || !xpp.getName().equals(enclosingTag)));

        if (matches.size() < args.length) {
            throw new ActionException(
                ErrorCode.ARGUMENT_VALUE_INVALID,
                "Invalid number of input or output arguments in XML message, expected "
                    + args.length + " but found " + matches.size()
            );
        }
        return matches;
    }

    protected ActionArgumentValue[] readArgumentValues(XmlPullParser xpp, ActionArgument[] args) throws Exception {
        // We're in the <ActionName>Response tag
        Map<String, String> matches = getMatchingNodes(xpp, args);

        ActionArgumentValue[] values = new ActionArgumentValue[args.length];

        for (int i = 0; i < args.length; i++) {

            ActionArgument arg = args[i];
            String value = findActionArgumentValue(matches, arg);
            if (value == null) {
                throw new ActionException(
                    ErrorCode.ARGUMENT_VALUE_INVALID,
                    "Could not find argument '" + arg.getName() + "' node");
            }

            log.fine("Reading action argument: " + arg.getName());
            values[i] = createValue(arg, value);
        }
        return values;
    }

    protected String findActionArgumentValue(Map<String, String> entries, ActionArgument arg) {
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (arg.isNameOrAlias(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    protected ActionException readFaultElement(XmlPullParser xpp) throws Exception {
        // We're in the "Fault" tag

        String errorCode = null;
        String errorDescription = null;

        XmlPullParserUtils.searchTag(xpp, "UPnPError");

        int event;
        do {
            event = xpp.next();
            if (event == XmlPullParser.START_TAG) {
                String tag = xpp.getName();
                if (tag.equals("errorCode")) {
                    errorCode = xpp.nextText();
                } else if (tag.equals("errorDescription")) {
                    errorDescription = xpp.nextText();
                }
            }
        }
        while (event != XmlPullParser.END_DOCUMENT && (event != XmlPullParser.END_TAG || !xpp.getName().equals("UPnPError")));

        if (errorCode != null) {
            try {
                int numericCode = Integer.valueOf(errorCode);
                ErrorCode standardErrorCode = ErrorCode.getByCode(numericCode);
                if (standardErrorCode != null) {
                    log.fine("Reading fault element: " + standardErrorCode.getCode() + " - " + errorDescription);
                    return new ActionException(standardErrorCode, errorDescription, false);
                } else {
                    log.fine("Reading fault element: " + numericCode + " - " + errorDescription);
                    return new ActionException(numericCode, errorDescription);
                }
            } catch (NumberFormatException ex) {
                throw new RuntimeException("Error code was not a number");
            }
        }

        throw new RuntimeException("Received fault element but no error code");
    }
}
