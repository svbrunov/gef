// $Id$
// Copyright (c) 1996-2005 The Regents of the University of California. All
// Rights Reserved. Permission to use, copy, modify, and distribute this
// software and its documentation without fee, and without a written
// agreement is hereby granted, provided that the above copyright notice
// and this paragraph appear in all copies.  This software program and
// documentation are copyrighted by The Regents of the University of
// California. The software program and documentation are supplied "AS
// IS", without any accompanying services from The Regents. The Regents
// does not warrant that the operation of the program will be
// uninterrupted or error-free. The end-user understands that the program
// was developed for research purposes and is advised not to rely
// exclusively on the program for any reason.  IN NO EVENT SHALL THE
// UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
// SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
// ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
// THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE POSSIBILITY OF
// SUCH DAMAGE. THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY
// WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE
// PROVIDED HEREUNDER IS ON AN "AS IS" BASIS, AND THE UNIVERSITY OF
// CALIFORNIA HAS NO OBLIGATIONS TO PROVIDE MAINTENANCE, SUPPORT,
// UPDATES, ENHANCEMENTS, OR MODIFICATIONS.

package org.tigris.gef.persistence.pgml;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.StringTokenizer;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.log4j.Logger;
import org.tigris.gef.base.Diagram;
import org.tigris.gef.presentation.Fig;
import org.tigris.gef.presentation.FigCircle;
import org.tigris.gef.presentation.FigEdge;
import org.tigris.gef.presentation.FigGroup;
import org.tigris.gef.presentation.FigLine;
import org.tigris.gef.presentation.FigPoly;
import org.tigris.gef.presentation.FigRRect;
import org.tigris.gef.presentation.FigRect;
import org.tigris.gef.presentation.FigText;
import org.tigris.gef.util.ColorFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Objects of this class can read an InputStream containing an XML
 * representation of a GEF diagram (PGML file) and produce the
 * equivalent GEF diagram object with all its contents.  This is
 * the functional equivalent of {@link org.argouml.persistence.PGMLParser}
 * but is supposed to provide a more robust foundation for clients to provide
 * extensions to support their custom diagram types.
 * @author Michael A. MacDonald
 */
public class PGMLStackParser implements HandlerStack, HandlerFactory {
    /**
     * Logger.
     */
    private static final Logger LOG = Logger.getLogger(PGMLStackParser.class);

    private static HashMap usedColors = new HashMap();

    private Stack handlerStack;
    private XMLReader xmiReader;
    private Map ownerRegistry;
    private Diagram diagram;
    private HashMap figRegistry;

    private HashMap translationTable = new HashMap();

    ////////////////////////////////////////////////////////////////
    // constructors

    /**
     * Builds a parser object with the appropriate identifier-to-model-object
     * mapping.
     * In general, a GEF diagram's objects can be associated with (owned by)
     * elements in an external model.  In PGML files, the associated
     * model element is represented by a unique string.  In reconstructing
     * a GEF diagram, a mapping between these unique strings
     * and the objects in the external model allows the association to be
     * rebuilt.
     * @param modelElementsByUuid A map that associates the unique string
     * identifier for the model objects with the model objects themselves
     */
    public PGMLStackParser(Map modelElementsByUuid) {
        ownerRegistry = modelElementsByUuid;
        diagram = null;
    }

    ////////////////////////////////////////////////////////////////
    // Public methods
    /**
     * Read a diagram from an input stream with the
     * default top-level ContentHandler,  {@link InitialHandler InitialHandler}.
     * is set to be the initial ContentHandler for the SAX parser.
     * @param is Stream that will deliver the PGML file
     * @param closeStream If true, the stream will be closed after the
     * PGML file is parsed
     * @see #readDiagram(InputStream, boolean, DefaultHandler)
     *
     * @return The read diagram.
     * @throws SAXException if something goes wrong.
     */
    public Diagram readDiagram(InputStream is, boolean closeStream)
    	throws SAXException {
        return readDiagram(is, closeStream, new InitialHandler(this));
    }

    /**
     * Read a diagram from an input stream with a user-specificed
     * ContentHandler as the initial ContentHandler for the SAX parser.
     * This allows the caller to completely control the processing of the
     * PGML file.
     *
     * @param is Stream that will deliver the PGML file
     * @param closeStream If true, the stream will be closed after the
     * PGML file is parsed
     * @param initialHandler The top level ContentHandler.  In order for
     * this method to work as expected, this ContentHandler must call the
     * {@link #setDiagram} method on this object with the read diagram.
     * @return The read diagram.
     * @throws SAXException if something goes wrong.
     */
    public synchronized Diagram readDiagram(InputStream is, boolean closeStream,
                                            DefaultHandler initialHandler)
        throws SAXException {
        handlerStack = new Stack();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            figRegistry = new HashMap();
            diagram = null;
            SAXParser pc = factory.newSAXParser();
            InputSource source = new InputSource(is);
            xmiReader = pc.getXMLReader();
            pc.parse(source, initialHandler);
            // source = null;
            if (closeStream) {
                // System.out.println(
                //     "closing stream now (in PGMLParser.readDiagram)");
                is.close();
            }
            return diagram;
        } catch (IOException e) {
            throw new SAXException(e);
        } catch (ParserConfigurationException e) {
            throw new SAXException(e);
        }
    }

    /**
     * Return the GEF diagram that has been associated with this object via
     * a previous call to {@link #setDiagram(Diagram)}, or <code>null</code> if
     * the diagram has not yet been set.
     *
     * @return Diagram object associated with this parser.
     */
    public Diagram getDiagram() {
        return diagram;
    }

    /**
     * The ContentHandler that actually creates a GEF diagram object in response
     * to a pgml element must call this method to set the diagram associated
     * with the parsing.
     *
     * @param theDiagram The diagram.
     */
    public void setDiagram(Diagram theDiagram) {
        diagram = theDiagram;
    }

    /**
     * Finds the external model object that corresponds to the unique string id.
     *
     * @see #PGMLStackParser(Map)
     *
     * @param id The id to search for.
     * @return The found object.
     */
    public Object findOwner(String id) {
        return ownerRegistry.get(id);
    }

    /**
     * @param from the type name to be "translated", i.e. replaced
     *             by something else
     * @param to   the resulting name
     */
    public void addTranslation(String from, String to) {
        translationTable.put(from, to);
    }


    ////////////////////////////////////////////////////////////////
    // HandlerStack implementation
    /**
     * @see org.tigris.gef.persistence.pgml.HandlerStack#pushHandlerStack(org.xml.sax.helpers.DefaultHandler)
     */
    public void pushHandlerStack(DefaultHandler handler) {
        handlerStack.push(handler);
        if (xmiReader != null) {
            xmiReader.setContentHandler(handler);
            xmiReader.setErrorHandler(handler);
        }
    }

    /**
     * @see org.tigris.gef.persistence.pgml.HandlerStack#popHandlerStack()
     */
    public void popHandlerStack() {
        handlerStack.pop();
        if (xmiReader != null && handlerStack.size() > 0) {
            DefaultHandler handler = (DefaultHandler) handlerStack.peek();
            xmiReader.setContentHandler(handler);
            xmiReader.setErrorHandler(handler);
        }
    }

    /**
     * Translate types that might appear in the PGML file.
     * Some elements or attributes in the PGML file may contain class names.
     * Before these names are interpreted to create object instances, they
     * are passed through this method.  The default implementation of the
     * method passes the names through by a map in order to determine if
     * translation is required.
     *
     * @param oldName Class name as it appears in PGML file
     * @return Class name appropriate for the current code base
     */
    public String translateType(String oldName) {
        String translated = (String) translationTable.get(oldName);

        if (translated == null) {
            return oldName;
        }

        return translated;
    }

    ////////////////////////////////////////////////////////////////
    // HandlerFactory implementation
    /**
     * Returns ContentHandler objects appropriate for the standard
     * set of elements that can appear within a PGML file.<p>
     *
     * First, the <em>description</em> attribute is checked for a PGML-style
     * class name specifier; if one is found, it is passed through the
     * {@link #translateType translateClassName} method and the resulting
     * class name is instantiated.  If the resulting object is itself
     * an instance of {@link HandlerFactory}, the method returns the
     * result of calling {@link HandlerFactory#getHandler getHandler} on that
     * object with the same arguments.  This allows a Fig object to take
     * complete control of its own parsing without imposing any change on
     * the global parsing framework.<p>
     *
     * If the element doesn't incorporate a class name, or
     * if the instanced object does not implement {@link HandlerFactory},
     * the element name is compared with one of PGML's special
     * element names.  If a known element name is found, either the element
     * is processed immediately and null returned, or the appropriate
     * handler for that element is returned.  If the element name is unknown,
     * null is returned.<p>
     *
     * @param stack Implementation of the stack of content handlers
     * @param container An object that provides context for the element (most
     * often by providing an implementation of the {@link Container} interface.
     * @param uri SAX uri argument
     * @param localname SAX local element name
     * @param qname SAX qualified element name
     * @param attributes The attributes that the SAX parser have identified
     * for the element.
     * @return ContentHandler object appropriate for the element, or null
     * if the element can be skipped
     *
     * @see org.tigris.gef.persistence.pgml.HandlerFactory#getHandler(
     *         org.argouml.gef.HandlerStack, java.lang.Object,
     *         java.lang.String, java.lang.String, java.lang.String,
     *         org.xml.sax.Attributes)
     */
    public DefaultHandler getHandler(HandlerStack stack,
                                      Object container,
        String uri, String localname, String qname,
        Attributes attributes)
    	throws SAXException {
        String clsNameBounds = attributes.getValue("description");
        Object elementInstance = null;
        if (clsNameBounds != null) {
            StringTokenizer st = new StringTokenizer(clsNameBounds, ",;[] ");
            String clsName = translateType(st.nextToken());
            try {
                // Special case here; FigLine appears in the description, but
                // there is no zero argoument constructor
                if (clsName.equals(FigLine.class.getName())) {
                    // TODO: Remove this when
                    // http://gef.tigris.org/issues/show_bug.cgi?id=219 is
                    // fixed and relevant version of GEF jar is in argouml CVS.
                    elementInstance = new FigLine(0, 0, 10, 10);
                } else {
                    elementInstance = Class.forName(clsName).newInstance();
                }
            } catch (ClassNotFoundException cnfe) {
                //TODO: Class not found! Why is this not considered an error?
                // shouldn't we throw an exception here.
                LOG.error("description:" + clsNameBounds
                          + " does not specify an available class.");
            } catch (IllegalAccessException iae) {
                throw new SAXException(iae);
            } catch (InstantiationException ie) {
                throw new SAXException(ie);
            }
            if (elementInstance instanceof HandlerFactory) {
                return ((HandlerFactory) elementInstance).getHandler(
                    stack, container, uri, localname, qname, attributes);
            }
        }

        // If we got here, one of the built-in handlers will apply
        if (qname.equals("group")) {
            if (elementInstance instanceof FigGroup) {
                return getGroupHandler(container, (FigGroup) elementInstance,
                                       attributes);
            }
            if (elementInstance instanceof FigEdge) {
                setAttrs((FigEdge) elementInstance, attributes);
                if (container instanceof Container) {
                    ((Container) container).addObject(elementInstance);
                }
                return new FigEdgeHandler(this, (FigEdge) elementInstance);
            }
        }

        if (qname.equals("text")) {
            if (elementInstance == null) {
                elementInstance = new FigText(0, 0, 100, 100);
            }
            if (elementInstance instanceof FigText) {
                FigText text = (FigText) elementInstance;
                setAttrs(text, attributes);
                if (container instanceof Container) {
                    ((Container) container).addObject(text);
                }
                String font = attributes.getValue("font");
                if (font != null && !font.equals("")) {
                    text.setFontFamily(font);
                }

                String textsize = attributes.getValue("textsize");
                if (textsize != null && !textsize.equals("")) {
                    int textsizeInt = Integer.parseInt(textsize);
                    text.setFontSize(textsizeInt);
                }
                return new FigTextHandler(this, text);
            }
        }

        if (qname.equals("path") || qname.equals("line")) {
            if (elementInstance == null) {
                elementInstance = new FigPoly();
            }
            if (elementInstance instanceof FigLine) {
                setAttrs((Fig) elementInstance, attributes);
                if (container instanceof Container) {
                    ((Container) container).addObject(elementInstance);
                }
                return new FigLineHandler(this, (FigLine) elementInstance);
            }
            if (elementInstance instanceof FigPoly) {
                setAttrs((Fig) elementInstance, attributes);
                if (container instanceof Container) {
                    ((Container) container).addObject(elementInstance);
                }
                return new FigPolyHandler(this, (FigPoly) elementInstance);
            }
        }

        if (qname.equals("private")) {
            if (elementInstance != null) {
                LOG.warn("private element unexpectedly generated instance: "
                         + elementInstance.toString());
            }
            if (container instanceof Container) {
                return new PrivateHandler(this, (Container) container);
            } else {
                LOG.warn("private element with inappropriate container: "
                         + container.toString());
            }
        }

        if (qname.equals("rectangle")) {
            String cornerRadius = attributes.getValue("rounding");
            int rInt = -1;
            if (cornerRadius != null && cornerRadius.length() > 0) {
                rInt = Integer.parseInt(cornerRadius);
            }
            if (elementInstance == null) {
                if (rInt >= 0) {
                    elementInstance = new FigRRect(0, 0, 80, 80);
                } else {
                    elementInstance = new FigRect(0, 0, 80, 80);
                }
            }
            if (elementInstance instanceof FigRRect && rInt >= 0) {
                ((FigRRect) elementInstance).setCornerRadius(rInt);
            }
            if (elementInstance instanceof Fig) {
                setAttrs((Fig) elementInstance, attributes);
                if (container instanceof Container) {
                    ((Container) container).addObject(elementInstance);
                }
                return null;
            }
        }

        if (qname.equals("ellipse")) {
            if (elementInstance == null) {
                elementInstance = new FigCircle(0, 0, 50, 50);
            }
            if (elementInstance instanceof FigCircle) {
                FigCircle f = (FigCircle) elementInstance;
                setAttrs(f, attributes);
                String rx = attributes.getValue("rx");
                String ry = attributes.getValue("ry");
                int rxInt =
                    (rx == null || rx.equals("")) ? 10 : Integer.parseInt(rx);
                int ryInt =
                    (ry == null || ry.equals("")) ? 10 : Integer.parseInt(ry);
                f.setBounds(f.getX() - rxInt, f.getY() - ryInt, rxInt * 2, ryInt * 2);

                return null;
            }
        }

        // Don't know what to do; throw up our hands--usually this
        // will mean that sub-elements are ignored
        LOG.info("Unrecognized element " + qname);
        if (elementInstance != null) {
            // Implement reasonable default behavior
            if (elementInstance instanceof Fig) {
                setAttrs((Fig) elementInstance, attributes);
            }
            if (container instanceof Container) {
                ((Container) container).addObject(elementInstance);
            }
        }
        return null;
    }

    /**
     * Associate a string with a Fig object, so the Fig object can be referenced
     * by the string later in the PGML file.  Default attribute processing
     * for elements that correspond to Fig objects calls this method,
     * passing the value of the <em>name</em> attribute as the name parameter.
     *
     * @see #setAttrs(Fig, Attributes)
     * @param fig Newly create Fig object
     * @param name String that may be used to reference Fig object later
     * in the PGML file
     */
    public void registerFig(Fig fig, String name) {
        figRegistry.put(name, fig);
    }

    /**
     * Find a Fig object with a name that has been previously registered
     * with {@link #registerFig registerFig}.
     *
     * @param name The name.
     * @return The Fig object found.
     */
    public Fig findFig(String name) {
        return (Fig) figRegistry.get(name);
    }

    /**
     * Sets the properties of a Fig object according to the values of the
     * attributes in the PGML-file element that specifies the Fig object.
     *
     * @param f
     * @param attrList
     */
    public static void setCommonAttrs(Fig f, Attributes attrList) {
        String x = attrList.getValue("x");
        if (x != null && !x.equals("")) {
            String y = attrList.getValue("y");
            String w = attrList.getValue("width");
            String h = attrList.getValue("height");
            int xInt = Integer.parseInt(x);
            int yInt = (y == null || y.equals("")) ? 0 : Integer.parseInt(y);
            int wInt = (w == null || w.equals("")) ? 20 : Integer.parseInt(w);
            int hInt = (h == null || h.equals("")) ? 20 : Integer.parseInt(h);

            f.setBounds(xInt, yInt, wInt, hInt);
        }

        String linewidth = attrList.getValue("stroke");
        if (linewidth != null && !linewidth.equals("")) {
            f.setLineWidth(Integer.parseInt(linewidth));
        }

        String strokecolor = attrList.getValue("strokecolor");
        if (strokecolor != null && !strokecolor.equals("")) {
            f.setLineColor(ColorFactory.getColor(strokecolor, Color.blue));
        }

        String fill = attrList.getValue("fill");
        if (fill != null && !fill.equals("")) {
            f.setFilled(fill.equals("1") || fill.startsWith("t"));
        }

        String fillcolor = attrList.getValue("fillcolor");
        if (fillcolor != null && !fillcolor.equals("")) {
            f.setFillColor(ColorFactory.getColor(fillcolor, Color.white));
        }

        String dasharray = attrList.getValue("dasharray");
        if (dasharray != null
                && !dasharray.equals("")
                && !dasharray.equals("solid")) {
            f.setDashed(true);
        }

        String context = attrList.getValue("context");
        if (context != null && !context.equals("")) {
            f.setContext(context);
        }

        String visState = attrList.getValue("shown");
        if (visState != null && !visState.equals("")) {
            int visStateInt = Integer.parseInt(visState);
            f.setVisible(visStateInt != 0);
        }

        String single = attrList.getValue("single");
        if (single != null && !single.equals("")) {
            f.setSingle(single);
        }
    }

    /**
     * Sets the properties of Fig objects according to a common set of
     * attributes found with Fig object elements in PGML files.
     * In addition to the attributes used by {@link #setCommonAttrs},
     * this methods registers the object with the value of the <em>name</em>
     * attribute with {@link #registerFig} and sets the object's owner
     * (the associate object in the external model) according to the map
     * supplied at construction and the value of the <em>href</em> attribute.
     *
     * @param f
     * @param attrList
     * @throws SAXException if something goes wrong.
     */
    public void setAttrs(Fig f, Attributes attrList) throws SAXException {
        String name = attrList.getValue("name");
        if (name != null && !name.equals("")) {
            figRegistry.put(name, f);
        }

        setCommonAttrs(f, attrList);

        String owner = attrList.getValue("href");
        if (owner != null && !owner.equals("")) {
            Object modelElement = findOwner(owner);
            if (modelElement == null) {
                throw new SAXException("Found href of " + owner + " with no matching element in model");
            }
            f.setOwner(modelElement);
        }
    }

    ////////////////////////////////////////////////////////////////
    // internal parsing methods
    /**
     * @param container
     * @param group
     * @param attributes
     * @return
     * @throws SAXException
     */
    private DefaultHandler getGroupHandler(Object container,
                                            FigGroup group,
                                            Attributes attributes)
    	throws SAXException {
        if (container instanceof Container) {
            ((Container) container).addObject(group);
        }
        StringTokenizer st =
            new StringTokenizer(attributes.getValue("description"), ",;[] ");
        setAttrs(group, attributes);
        if (st.hasMoreElements()) {
            st.nextToken();
        }
        String xStr = null;
        String yStr = null;
        String wStr = null;
        String hStr = null;
        if (st.hasMoreElements()) {
            xStr = st.nextToken();
            yStr = st.nextToken();
            wStr = st.nextToken();
            hStr = st.nextToken();
        }
        if (xStr != null && !xStr.equals("")) {
            int x = Integer.parseInt(xStr);
            int y = Integer.parseInt(yStr);
            int w = Integer.parseInt(wStr);
            int h = Integer.parseInt(hStr);
            group.setBounds(x, y, w, h);
        }
        return new FigGroupHandler(this, group);
    }
}

