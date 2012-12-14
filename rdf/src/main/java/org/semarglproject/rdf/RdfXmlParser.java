/*
 * Copyright 2012 Lev Khomich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.semarglproject.rdf;

import org.semarglproject.ri.IRI;
import org.semarglproject.ri.MalformedIRIException;
import org.semarglproject.vocab.RDF;
import org.semarglproject.xml.XmlUtils;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public final class RdfXmlParser implements SaxSink, TripleSource {

    private static final short INSIDE_OF_PROPERTY = 1;
    private static final short INSIDE_OF_RESOURCE = 2;
    private static final short PARSE_TYPE_LITERAL = 3;
    private static final short PARSE_TYPE_COLLECTION = 4;
    private static final short PARSE_TYPE_RESOURCE = 5;

    private short mode = 0;

    private TripleSink sink = null;
    private String baseUri = "";

    private final Stack<Short> modeStack = new Stack<Short>();
    private final Stack<String> langStack = new Stack<String>();
    private final Stack<String> baseStack = new Stack<String>();
    private final Stack<String> subjStack = new Stack<String>();
    private final Stack<Integer> subjLiIndexStack = new Stack<Integer>();
    private final Map<String, String> nsMappings = new HashMap<String, String>();

    private int bnodeId = 0;
    private String subjRes = null;     // IRI or bnode
    private String seqTailRes = null;  // tail node of parseType="Collection"
    private String predIri = null;     // predicate IRI
    private String datatypeIri = null; // typed literal datatype IRI
    private String reifyIri = null;
    private boolean captureLiteral = false;

    private int parseDepth = 0;
    private StringBuilder parse = new StringBuilder();

    private static void error(String msg) throws SAXException {
        throw new SAXException(new ParseException(msg));
    }

    @SuppressWarnings("deprecation")
    private boolean violatesSchema(String nodeIri) {
        return nodeIri == null || nodeIri.isEmpty() || nodeIri.equals(RDF.PARSE_TYPE)
                || nodeIri.equals(RDF.ABOUT_EACH) || nodeIri.equals(RDF.DATATYPE)
                || nodeIri.equals(RDF.BAG_ID) || nodeIri.equals(RDF.ABOUT)
                || nodeIri.equals(RDF.RESOURCE) || nodeIri.equals(RDF.NODEID)
                || nodeIri.equals(RDF.ID) || nodeIri.equals(RDF.ABOUT_EACH_PREFIX);
    }

    @Override
    public void startElement(String nsUri, String lname, String qname, Attributes attrs) throws SAXException {
        modeStack.push(mode);
        if (parseDepth > 0) {
            parseDepth++;
            if (mode == PARSE_TYPE_LITERAL) {
                parse.append(XmlUtils.serializeOpenTag(nsUri, qname, nsMappings, attrs, true));
                nsMappings.clear();
                return;
            }
        }

        processLangAndBase(attrs);

        String iri = nsUri + lname;
        if (subjRes == null && (nsUri == null || nsUri.isEmpty()) || iri.equals(RDF.RDF)) {
            return;
        }
        if (violatesSchema(iri)) {
            error(qname + " is not allowed here");
        }

        switch (mode) {
            case PARSE_TYPE_COLLECTION:
            case INSIDE_OF_PROPERTY: {
                subjRes = getSubject(attrs);

                if (mode != PARSE_TYPE_COLLECTION && !subjStack.isEmpty()) {
                    processNonLiteralTriple(subjStack.peek(), predIri, subjRes);
                }

                if (!iri.equals(RDF.DESCRIPTION)) {
                    if (iri.equals(RDF.LI)) {
                        error(qname + " is not allowed here");
                    }
                    sink.addIriRef(subjRes, RDF.TYPE, iri);
                }

                for (int i = 0; i < attrs.getLength(); i++) {
                    String tag = attrs.getURI(i) + attrs.getLocalName(i);
                    if (tag.equals(RDF.NODEID) || tag.equals(RDF.ABOUT) || tag.equals(RDF.ID)
                            || attrs.getQName(i).startsWith(XMLConstants.XML_NS_PREFIX)) {
                        continue;
                    }
                    String value = attrs.getValue(i);
                    if (tag.equals(RDF.TYPE)) {
                        sink.addIriRef(subjRes, RDF.TYPE, value);
                    } else {
                        if (violatesSchema(tag) || tag.equals(RDF.LI)) {
                            error(qname + " is not allowed here");
                        }
                        sink.addPlainLiteral(subjRes, tag, value, langStack.peek());
                    }
                }

                subjStack.push(subjRes);
                subjLiIndexStack.push(1);
                if (mode == INSIDE_OF_PROPERTY) {
                    mode = INSIDE_OF_RESOURCE;
                }
                break;
            }
            case PARSE_TYPE_RESOURCE:
            case INSIDE_OF_RESOURCE: {
                int liIndex = subjLiIndexStack.pop();

                if (iri.equals(RDF.NIL) || iri.equals(RDF.DESCRIPTION)) {
                    error(qname + " is not allowed here");
                }
                if (!IRI.isAbsolute(iri)) {
                    error("Invalid property IRI");
                }

                predIri = iri;
                if (predIri.equals(RDF.LI)) {
                    predIri = RDF.NS + "_" + liIndex++;
                }
                subjLiIndexStack.push(liIndex);

                String nodeId = attrs.getValue(RDF.NS, "ID");
                if (nodeId != null) {
                    reifyIri = resolveIRINoResolve(baseStack.peek(), nodeId);
                }

                if (attrs.getValue(RDF.NS, "resource") != null && attrs.getValue(RDF.NS, "nodeID") != null) {
                    error("Both rdf:resource and rdf:nodeID are present");
                }
                if (attrs.getValue(RDF.NS, "parseType") != null && !isAttrsValidForParseType(attrs)) {
                    error("rdf:parseType conflicts with other attributes");
                }

                captureLiteral = true;
                mode = INSIDE_OF_PROPERTY;
                for (int i = 0; i < attrs.getLength(); i++) {
                    String attr = attrs.getURI(i) + attrs.getLocalName(i);
                    if (attrs.getQName(i).startsWith(XMLConstants.XML_NS_PREFIX) || attr.equals(RDF.ID)) {
                        continue;
                    }
                    processPropertyTagAttr(nsUri, attr, attrs.getValue(i));
                }
                if (captureLiteral) {
                    parse = new StringBuilder();
                }
                break;
            }
        }
    }

    private void processLangAndBase(Attributes attrs) throws SAXException {
        String lang = langStack.peek();
        if (attrs.getValue(XmlUtils.XML_LANG) != null) {
            lang = attrs.getValue(XmlUtils.XML_LANG);
        }
        langStack.push(lang);

        String base = baseStack.peek();
        if (attrs.getValue(XmlUtils.XML_BASE) != null) {
            base = attrs.getValue(XmlUtils.XML_BASE);
            if (base.contains("#")) {
                base = base.substring(0, base.lastIndexOf('#'));
            }
            base += '#';
            if (!IRI.isAbsolute(base)) {
                error("Invalid base IRI");
            }
        }
        baseStack.push(base);
    }

    private void processPropertyTagAttr(String nsUri, String attr, String value) throws SAXException {
        if (attr.equals(RDF.RESOURCE)) {
            String id = resolveIRI(baseStack.peek(), value);
            sink.addIriRef(subjRes, predIri, id);
            processNonLiteralTriple(subjRes, predIri, id);
            captureLiteral = false;
        } else if (attr.equals(RDF.DATATYPE)) {
            datatypeIri = resolveIRINoResolve(nsUri, value);
        } else if (attr.equals(RDF.PARSE_TYPE)) {
            parseDepth = 1;
            if (value.equalsIgnoreCase("Literal")) {
                parse = new StringBuilder();
                mode = PARSE_TYPE_LITERAL;
            } else if (value.equalsIgnoreCase("Resource")) {
                String bnode = RDF.BNODE_PREFIX + bnodeId++;
                processNonLiteralTriple(subjRes, predIri, bnode);
                subjRes = bnode;
                subjStack.push(subjRes);
                subjLiIndexStack.push(1);
                mode = PARSE_TYPE_RESOURCE;
            } else if (value.equalsIgnoreCase("Collection")) {
                String bnode = RDF.BNODE_PREFIX + bnodeId++;
                sink.addNonLiteral(subjRes, predIri, bnode);
                subjRes = bnode;
                seqTailRes = null;
                subjStack.push(bnode);
                subjLiIndexStack.push(1);
                mode = PARSE_TYPE_COLLECTION;
            }
            captureLiteral = false;
        } else if (attr.equals(RDF.NODEID)) {
            if (!XmlUtils.isValidNCName(value)) {
                error("Invalid nodeID");
            }
            String id = RDF.BNODE_PREFIX + value.hashCode();
            processNonLiteralTriple(subjRes, predIri, id);
            captureLiteral = false;
        } else {
            if (violatesSchema(attr) || attr.equals(RDF.NIL)) {
                error(attr + " is not allowed here");
            }
            String bnode = RDF.BNODE_PREFIX + bnodeId++;
            processNonLiteralTriple(subjRes, predIri, bnode);
            sink.addPlainLiteral(bnode, attr, value, langStack.peek());
            captureLiteral = false;
        }
    }

    @Override
    public void endElement(String namespaceUri, String lname, String qname) throws SAXException {
        if (parseDepth > 0) {
            parseDepth--;
            if (mode == PARSE_TYPE_LITERAL && parseDepth > 0) {
                parse.append("</").append(qname).append(">");
                return;
            }
        }
        if (subjStack.isEmpty()) {
            return;
        }

        switch (mode) {
            case PARSE_TYPE_RESOURCE:
            case INSIDE_OF_RESOURCE: {
                subjStack.pop();
                if (!subjStack.isEmpty()) {
                    subjRes = subjStack.peek();
                }
                subjLiIndexStack.pop();
                if (mode == INSIDE_OF_RESOURCE) {
                    mode = INSIDE_OF_PROPERTY;
                } else {
                    mode = INSIDE_OF_RESOURCE;
                }
                break;
            }
            case PARSE_TYPE_COLLECTION: {
                subjStack.pop();
                subjLiIndexStack.pop();
                if (parseDepth > 0) {
                    if (seqTailRes == null) {
                        seqTailRes = subjStack.peek();
                        sink.addNonLiteral(seqTailRes, RDF.FIRST, subjRes);
                    } else {
                        String bnode = RDF.BNODE_PREFIX + bnodeId++;
                        sink.addNonLiteral(bnode, RDF.FIRST, subjRes);
                        sink.addNonLiteral(seqTailRes, RDF.REST, bnode);
                        seqTailRes = bnode;
                    }
                } else {
                    sink.addIriRef(seqTailRes, RDF.REST, RDF.NIL);
                    if (!subjStack.isEmpty()) {
                        subjRes = subjStack.peek();
                    }
                    mode = INSIDE_OF_RESOURCE;
                }
                break;
            }
            case INSIDE_OF_PROPERTY: {
                if (captureLiteral) {
                    String value = parse.toString();
                    if (datatypeIri != null) {
                        processLiteralTriple(subjRes, predIri, value, datatypeIri, true);
                    } else {
                        processLiteralTriple(subjRes, predIri, value, langStack.peek(), false);
                    }
                    captureLiteral = false;
                }
                mode = INSIDE_OF_RESOURCE;
                break;
            }
            case PARSE_TYPE_LITERAL: {
                processLiteralTriple(subjRes, predIri, parse.toString(), RDF.XML_LITERAL, true);
                mode = INSIDE_OF_RESOURCE;
                break;
            }
        }
        langStack.pop();
        baseStack.pop();
        // TODO: fix modeStack
        short savedMode = modeStack.pop();
        if (savedMode == PARSE_TYPE_RESOURCE) {
            mode = savedMode;
        }
    }

    private boolean isAttrsValidForParseType(Attributes attrs) {
        for (int i = 0; i < attrs.getLength(); i++) {
            if (attrs.getQName(i).startsWith("xml")) {
                continue;
            }
            String uri = attrs.getURI(i) + attrs.getLocalName(i);
            if (uri.equals(RDF.PARSE_TYPE) || uri.equals(RDF.ID)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private void processNonLiteralTriple(String subj, String pred, String obj) {
        sink.addNonLiteral(subj, pred, obj);
        if (reifyIri != null) {
            sink.addIriRef(reifyIri, RDF.TYPE, RDF.STATEMENT);
            sink.addNonLiteral(reifyIri, RDF.SUBJECT, subj);
            sink.addIriRef(reifyIri, RDF.PREDICATE, pred);
            sink.addNonLiteral(reifyIri, RDF.OBJECT, obj);
            reifyIri = null;
        }
    }

    private void processLiteralTriple(String subj, String pred, String value, String langOrDt,
                                      boolean typed) {
        if (typed) {
            sink.addTypedLiteral(subj, pred, value, langOrDt);
        } else {
            sink.addPlainLiteral(subj, pred, value, langOrDt);
        }
        if (reifyIri != null) {
            sink.addIriRef(reifyIri, RDF.TYPE, RDF.STATEMENT);
            sink.addNonLiteral(reifyIri, RDF.SUBJECT, subj);
            sink.addIriRef(reifyIri, RDF.PREDICATE, pred);
            if (typed) {
                sink.addTypedLiteral(reifyIri, RDF.OBJECT, value, langOrDt);
            } else {
                sink.addPlainLiteral(reifyIri, RDF.OBJECT, value, langOrDt);
            }
            reifyIri = null;
        }
    }

    private String getSubject(Attributes attrs) throws SAXException {
        int count = 0;
        String result = null;
        String attrValue = attrs.getValue(RDF.NS, "about");
        if (attrValue != null) {
            result = resolveIRI(baseStack.peek(), attrValue);
            count++;
        }
        attrValue = attrs.getValue(RDF.NS, "ID");
        if (attrValue != null) {
            result = resolveIRINoResolve(baseStack.peek(), attrValue);
            count++;
        }
        attrValue = attrs.getValue(RDF.NS, "nodeID");
        if (attrValue != null) {
            result = RDF.BNODE_PREFIX + attrValue.hashCode();
            count++;
        }
        if (count == 0) {
            return RDF.BNODE_PREFIX + bnodeId++;
        }
        if (count > 1) {
            error("Ambiguous identifier definition");
        }
        return result;
    }

    private static String resolveIRINoResolve(String nsIri, String iri) throws SAXException {
        if (IRI.isAbsolute(iri)) {
            return iri;
        }
        if (!XmlUtils.isValidNCName(iri)) {
            throw new SAXException(new ParseException("Vocab term must be a valid NCName"));
        }
        String result = nsIri + iri;
        if (IRI.isAbsolute(result)) {
            return result;
        }
        throw new SAXException(new ParseException("Malformed IRI: " + iri,
                new MalformedIRIException("Can not parse IRI")));
    }

    private static String resolveIRI(String nsIri, String iri) throws SAXException {
        try {
            return IRI.resolve(nsIri, iri);
        } catch (MalformedIRIException e) {
            throw new SAXException(new ParseException("Malformed IRI: " + iri, e));
        }
    }

    @Override
    public void startDocument() throws SAXException {
        mode = INSIDE_OF_PROPERTY;
        baseStack.push(baseUri);
        langStack.push(null);
        captureLiteral = false;
        subjRes = null;
        seqTailRes = null;
        predIri = null;
        datatypeIri = null;
        reifyIri = null;
        parseDepth = 0;
    }

    @Override
    public void endDocument() throws SAXException {
        langStack.clear();
        baseStack.clear();
        subjStack.clear();
        modeStack.clear();
        subjLiIndexStack.clear();
        nsMappings.clear();
        parse = new StringBuilder();
    }

    @Override
    public void characters(char[] buffer, int offset, int length) throws SAXException {
        if (mode == PARSE_TYPE_LITERAL || captureLiteral) {
            parse.append(String.copyValueOf(buffer, offset, length));
        }
    }

    @Override
    public void ignorableWhitespace(char[] buffer, int offset, int length) throws SAXException {
        characters(buffer, offset, length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        if (parseDepth > 0 && mode == PARSE_TYPE_LITERAL) {
            parse.append("<?").append(target).append(" ").append(data).append("?>");
        }
    }

    @Override
    public void comment(char[] buffer, int offset, int length) throws SAXException {
        if (parseDepth > 0 && mode == PARSE_TYPE_LITERAL) {
            parse.append("<!--");
            parse.append(String.copyValueOf(buffer, offset, length));
            parse.append("-->");
        }
    }

    @Override
    public void startPrefixMapping(String abbr, String uri) throws SAXException {
        if (mode == PARSE_TYPE_LITERAL) {
            nsMappings.put(abbr, uri);
        }
    }

    @Override
    public void setBaseUri(String baseUri) {
        if (baseUri != null && !baseUri.isEmpty() && Character.isLetter(baseUri.charAt(baseUri.length() - 1))) {
            this.baseUri = baseUri + "#";
        } else {
            this.baseUri = baseUri == null ? "" : baseUri;
        }
    }

    @Override
    public void startStream() {
        sink.startStream();
    }

    @Override
    public void endStream() {
        sink.endStream();
    }

    @Override
    public void setDocumentLocator(Locator arg0) {
    }

    @Override
    public void skippedEntity(String arg0) throws SAXException {
    }

    @Override
    public void endPrefixMapping(String arg0) throws SAXException {
    }

    @Override
    public void endCDATA() throws SAXException {
    }

    @Override
    public void endDTD() throws SAXException {
    }

    @Override
    public void endEntity(String arg0) throws SAXException {
    }

    @Override
    public void startCDATA() throws SAXException {
    }

    @Override
    public void startDTD(String arg0, String arg1, String arg2) throws SAXException {
    }

    @Override
    public void startEntity(String arg0) throws SAXException {
    }

    public RdfXmlParser streamingTo(TripleSink sink) {
        this.sink = sink;
        return this;
    }

    @Override
    public ParseException processException(SAXException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ParseException) {
            return (ParseException) cause;
        }
        return new ParseException(e);
    }
}
