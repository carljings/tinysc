package io.tinysc.deployment;

import io.tinysc.deployment.model.WebAppDescriptor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WebXmlParser {
    public WebAppDescriptor parseWebRoot(Path webRoot) throws DeploymentException {
        Path descriptor = webRoot.resolve("WEB-INF").resolve("web.xml");
        if (!Files.exists(descriptor)) {
            return WebAppDescriptor.builder().build();
        }
        try (InputStream input = Files.newInputStream(descriptor)) {
            return parse(input, descriptor.toString());
        } catch (IOException exception) {
            throw new DeploymentException("cannot read " + descriptor, exception);
        }
    }

    public WebAppDescriptor parse(InputStream input, String sourceName) throws DeploymentException {
        try {
            DocumentBuilder builder = secureFactory().newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                    new InputSource(new StringReader("")));
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            Document document = builder.parse(input);
            Element root = document.getDocumentElement();
            if (root == null || !"web-app".equals(localName(root))) {
                throw new DeploymentException(sourceName + " does not contain a web-app root element");
            }
            return toModel(root);
        } catch (DeploymentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DeploymentException("cannot parse " + sourceName + ": "
                    + exception.getMessage(), exception);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException exception) {
            throw new ParserConfigurationException(
                    "XML parser does not support external access restrictions: "
                            + exception.getMessage());
        }
        return factory;
    }

    private static WebAppDescriptor toModel(Element root) {
        WebAppDescriptor.Builder result = WebAppDescriptor.builder()
                .version(root.getAttribute("version"))
                .metadataComplete(Boolean.parseBoolean(root.getAttribute("metadata-complete")));

        for (Element element : children(root, "context-param")) {
            result.contextParam(requiredText(element, "param-name"),
                    textOrEmpty(element, "param-value"));
        }
        for (Element element : children(root, "listener")) {
            result.listenerClass(requiredText(element, "listener-class"));
        }
        for (Element element : children(root, "filter")) {
            result.filter(new WebAppDescriptor.FilterDefinition(
                    requiredText(element, "filter-name"),
                    requiredText(element, "filter-class"),
                    initParams(element),
                    booleanText(element, "async-supported", false)));
        }
        for (Element element : children(root, "filter-mapping")) {
            result.filterMapping(new WebAppDescriptor.FilterMapping(
                    requiredText(element, "filter-name"),
                    texts(element, "url-pattern"),
                    texts(element, "servlet-name"),
                    upperTexts(element, "dispatcher")));
        }
        for (Element element : children(root, "servlet")) {
            String loadValue = optionalText(element, "load-on-startup");
            Integer loadOnStartup = loadValue == null || loadValue.isEmpty()
                    ? null : Integer.valueOf(loadValue);
            result.servlet(new WebAppDescriptor.ServletDefinition(
                    requiredText(element, "servlet-name"),
                    optionalText(element, "servlet-class"),
                    optionalText(element, "jsp-file"),
                    initParams(element),
                    loadOnStartup,
                    booleanText(element, "async-supported", false),
                    multipartConfig(element)));
        }
        for (Element element : children(root, "servlet-mapping")) {
            result.servletMapping(new WebAppDescriptor.ServletMapping(
                    requiredText(element, "servlet-name"), texts(element, "url-pattern")));
        }
        List<Element> sessionConfigs = children(root, "session-config");
        if (!sessionConfigs.isEmpty()) {
            String timeout = optionalText(sessionConfigs.get(0), "session-timeout");
            if (timeout != null && !timeout.isEmpty()) {
                result.sessionTimeoutMinutes(Integer.parseInt(timeout));
            }
        }
        List<Element> welcomeLists = children(root, "welcome-file-list");
        if (!welcomeLists.isEmpty()) {
            for (String welcome : texts(welcomeLists.get(0), "welcome-file")) {
                result.welcomeFile(welcome);
            }
        }
        for (Element element : children(root, "error-page")) {
            result.errorPage(errorPage(element));
        }
        return result.build();
    }

    private static WebAppDescriptor.ErrorPageDefinition errorPage(Element element) {
        List<Element> codes = children(element, "error-code");
        List<Element> exceptions = children(element, "exception-type");
        List<Element> locations = children(element, "location");
        if (codes.size() > 1 || exceptions.size() > 1
                || (!codes.isEmpty() && !exceptions.isEmpty())) {
            throw new IllegalArgumentException(
                    "error-page must declare at most one error-code or exception-type");
        }
        if (locations.size() != 1) {
            throw new IllegalArgumentException(
                    "error-page must declare exactly one location");
        }

        Integer code = null;
        if (!codes.isEmpty()) {
            String rawCode = codes.get(0).getTextContent().trim();
            if (!rawCode.matches("[0-9]{3}")) {
                throw new IllegalArgumentException(
                        "error-code must contain exactly three digits");
            }
            code = Integer.valueOf(rawCode);
            if (code.intValue() == 0) {
                throw new IllegalArgumentException("error-code must not be zero");
            }
        }

        String exceptionType = null;
        if (!exceptions.isEmpty()) {
            exceptionType = exceptions.get(0).getTextContent().trim();
            if (exceptionType.isEmpty()) {
                throw new IllegalArgumentException("exception-type must not be empty");
            }
        }
        return new WebAppDescriptor.ErrorPageDefinition(
                code, exceptionType, locations.get(0).getTextContent().trim());
    }

    private static WebAppDescriptor.MultipartConfigDefinition multipartConfig(Element servlet) {
        List<Element> configurations = children(servlet, "multipart-config");
        if (configurations.isEmpty()) {
            return null;
        }
        Element configuration = configurations.get(0);
        String maxFileSize = optionalText(configuration, "max-file-size");
        String maxRequestSize = optionalText(configuration, "max-request-size");
        String fileSizeThreshold = optionalText(configuration, "file-size-threshold");
        return new WebAppDescriptor.MultipartConfigDefinition(
                textOrEmpty(configuration, "location"),
                maxFileSize == null ? -1L : Long.parseLong(maxFileSize),
                maxRequestSize == null ? -1L : Long.parseLong(maxRequestSize),
                fileSizeThreshold == null ? 0 : Integer.parseInt(fileSizeThreshold));
    }

    private static Map<String, String> initParams(Element parent) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Element element : children(parent, "init-param")) {
            String name = requiredText(element, "param-name");
            if (result.put(name, textOrEmpty(element, "param-value")) != null) {
                throw new IllegalArgumentException("duplicate init-param: " + name);
            }
        }
        return result;
    }

    private static boolean booleanText(Element parent, String name, boolean defaultValue) {
        String value = optionalText(parent, name);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static List<String> upperTexts(Element parent, String name) {
        List<String> result = texts(parent, name);
        for (int index = 0; index < result.size(); index++) {
            result.set(index, result.get(index).toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private static List<String> texts(Element parent, String name) {
        List<String> result = new ArrayList<String>();
        for (Element child : children(parent, name)) {
            String value = child.getTextContent().trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static String requiredText(Element parent, String name) {
        String value = optionalText(parent, name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    private static String optionalText(Element parent, String name) {
        List<Element> matches = children(parent, name);
        return matches.isEmpty() ? null : matches.get(0).getTextContent().trim();
    }

    private static String textOrEmpty(Element parent, String name) {
        String value = optionalText(parent, name);
        return value == null ? "" : value;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<Element>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(node))) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }
}
