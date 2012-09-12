package com.redhat.ceylon.compiler.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.redhat.ceylon.compiler.typechecker.analyzer.ModuleManager;
import com.redhat.ceylon.compiler.typechecker.model.BottomType;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.FunctionalParameter;
import com.redhat.ceylon.compiler.typechecker.model.Getter;
import com.redhat.ceylon.compiler.typechecker.model.Interface;
import com.redhat.ceylon.compiler.typechecker.model.IntersectionType;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.MethodOrValue;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.ParameterList;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.Scope;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.TypeParameter;
import com.redhat.ceylon.compiler.typechecker.model.UnionType;
import com.redhat.ceylon.compiler.typechecker.model.Unit;
import com.redhat.ceylon.compiler.typechecker.model.Value;
import com.redhat.ceylon.compiler.typechecker.model.ValueParameter;

public class JsonPackage extends com.redhat.ceylon.compiler.typechecker.model.Package {

    //Ugly hack to have a ref to IdentifiableObject at hand, to use as implicit supertype of classes
    private final static Map<String,Object> idobj = new HashMap<String, Object>();
    //This is to use as the implicit supertype of interfaces
    private final static Map<String,Object> objclass = new HashMap<String, Object>();
    //This is for type parameters
    private final static Map<String,Object> voidclass = new HashMap<String, Object>();
    private Map<String,Object> model;
    private final Unit unit = new Unit();
    private final String pkgname;
    private boolean loaded = false;
    private BottomType bottom;

    static {
        idobj.put(MetamodelGenerator.KEY_NAME, "IdentifiableObject");
        idobj.put(MetamodelGenerator.KEY_PACKAGE, "ceylon.language");
        idobj.put(MetamodelGenerator.KEY_MODULE, "ceylon.language");
        objclass.put(MetamodelGenerator.KEY_NAME, "Object");
        objclass.put(MetamodelGenerator.KEY_PACKAGE, "ceylon.language");
        objclass.put(MetamodelGenerator.KEY_MODULE, "ceylon.language");
        voidclass.put(MetamodelGenerator.KEY_NAME, "Void");
        voidclass.put(MetamodelGenerator.KEY_PACKAGE, "ceylon.language");
        voidclass.put(MetamodelGenerator.KEY_MODULE, "ceylon.language");
    }
    public JsonPackage(String pkgname) {
        this.pkgname = pkgname;
        setName(ModuleManager.splitModuleName(pkgname));
        unit.setPackage(this);
        unit.setFilename("package.ceylon");
        addUnit(unit);
    }
    void setModel(Map<String, Object> metamodel) {
        model = metamodel;
    }

    void loadDeclarations() {
        if (loaded) return;
        loaded = true;
        if (getModule().getLanguageModule() == getModule() && "ceylon.language".equals(pkgname)) {
            //Mark the language module as immediately available to bypass certain validations
            getModule().setAvailable(true);
            //Ugly ass hack - add Bottom to the model
            bottom = new BottomType(unit);
            bottom.setContainer(this);
            bottom.setUnit(unit);
            System.out.println("marking langmod available - SHOULD HAPPEN ONLY ONCE");
        }
        setShared(model.get("$pkg-shared") != null);
        for (Map.Entry<String,Object> e : model.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("$pkg-")) {
                @SuppressWarnings("unchecked")
                Map<String,Object> m = (Map<String,Object>)e.getValue();
                String metatype = (String)m.get(MetamodelGenerator.KEY_METATYPE);
                if (metatype == null) {
                    throw new IllegalArgumentException("Missing metatype from entry " + m);
                }
                if (MetamodelGenerator.METATYPE_CLASS.equals(metatype)) {
                    loadClass(e.getKey(), m, this, null);
                } else if (MetamodelGenerator.METATYPE_INTERFACE.equals(metatype)) {
                    loadInterface(e.getKey(), m, this, null);
                } else if (metatype.equals(MetamodelGenerator.METATYPE_ATTRIBUTE)
                        || metatype.equals(MetamodelGenerator.METATYPE_GETTER)) {
                    loadAttribute(k, m, this, null);
                } else if (metatype.equals(MetamodelGenerator.METATYPE_METHOD)) {
                    loadMethod(k, m, this, null);
                } else if (metatype.equals(MetamodelGenerator.METATYPE_OBJECT)) {
                    loadObject(k, m, this, null);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private com.redhat.ceylon.compiler.typechecker.model.Class loadClass(String name, Map<String, Object> m,
            Scope parent, final List<TypeParameter> existing) {
        //Check if it's already been added first
        Declaration maybe = parent.getDirectMember(name, null);
        if (maybe instanceof com.redhat.ceylon.compiler.typechecker.model.Class) {
            return (com.redhat.ceylon.compiler.typechecker.model.Class)maybe;
        }
        //It's not there, so create it
        com.redhat.ceylon.compiler.typechecker.model.Class cls = new com.redhat.ceylon.compiler.typechecker.model.Class();
        cls.setAbstract(m.containsKey("abstract"));
        setDefaultSharedActualFormal(cls, m);
        cls.setAnonymous(m.containsKey("$anon"));
        cls.setContainer(parent);
        cls.setName(name);
        cls.setUnit(unit);
        if (parent == this) {
            unit.addDeclaration(cls);
        }
        //Type parameters are about the first thing we need to load
        final List<TypeParameter> tparms = parseTypeParameters(
                (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_PARAMS), cls, existing);
        if (tparms != null) {
            cls.setTypeParameters(tparms);
        }
        final List<TypeParameter> allparms = JsonPackage.merge(existing, tparms);
        //This is to avoid circularity
        if (!(getModule().getLanguageModule()==getModule() && ("Bottom".equals(name) || "Void".equals(name)))) {
            if (m.containsKey("super")) {
                cls.setExtendedType(getTypeFromJson((Map<String,Object>)m.get("super"), allparms));
            } else {
                cls.setExtendedType(getTypeFromJson(idobj, allparms));
            }
        }
        if (m.containsKey(MetamodelGenerator.KEY_SELF_TYPE)) {
            cls.setSelfType(getTypeFromJson((Map<String,Object>)m.get(MetamodelGenerator.KEY_SELF_TYPE), allparms));
        }

        ParameterList plist = parseParameters((List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_PARAMS),
                cls, allparms);
        plist.setNamedParametersSupported(true);
        cls.setParameterList(plist);
        if (m.containsKey("of")) {
            cls.setCaseTypes(parseTypeList((List<Map<String,Object>>)m.get("of"), allparms));
        }
        if (m.containsKey("satisfies")) {
            cls.setSatisfiedTypes(parseTypeList((List<Map<String,Object>>)m.get("satisfies"), allparms));
        }
        if (m.containsKey(MetamodelGenerator.KEY_INTERFACES)) {
            System.out.println("clase " + name + " tiene interfaces: " + m.get(MetamodelGenerator.KEY_INTERFACES));
        }
        if (m.containsKey(MetamodelGenerator.KEY_CLASSES)) {
            System.out.println("clase " + name + " tiene clases: " + m.get(MetamodelGenerator.KEY_CLASSES));
        }
        if (m.containsKey(MetamodelGenerator.KEY_OBJECTS)) {
            System.out.println("clase " + name + " tiene objetos: " + m.get(MetamodelGenerator.KEY_OBJECTS));
        }
        addAttributesAndMethods(m, cls, allparms);
        return cls;
    }

    /** Parses the specified attributes and methods from JSON data and adds them to the specified declaration. */
    @SuppressWarnings("unchecked")
    private void addAttributesAndMethods(Map<String,Object> m, Declaration d, List<TypeParameter> tparms) {
        //Attributes
        Map<String, Map<String,Object>> sub = (Map<String,Map<String,Object>>)m.get(MetamodelGenerator.KEY_ATTRIBUTES);
        if (sub != null) {
            for(Map.Entry<String, Map<String,Object>> e : sub.entrySet()) {
                d.getMembers().add(loadAttribute(e.getKey(), e.getValue(), (Scope)d, tparms));
            }
        }
        //Methods
        sub = (Map<String,Map<String,Object>>)m.get(MetamodelGenerator.KEY_METHODS);
        if (sub != null) {
            for(Map.Entry<String, Map<String,Object>> e : sub.entrySet()) {
                d.getMembers().add(loadMethod(e.getKey(), e.getValue(), (Scope)d, tparms));
            }
        }
    }

    /** Creates a list of ProducedType from the references in the maps.
     * @param types A list of maps where each map is a reference to a type or type parameter.
     * @param typeParams The type parameters that can be referenced from the list of maps. */
    private List<ProducedType> parseTypeList(List<Map<String,Object>> types, List<TypeParameter> typeParams) {
        List<ProducedType> ts = new ArrayList<ProducedType>(types.size());
        for (Map<String,Object> st : types) {
            ts.add(getTypeFromJson(st, typeParams));
        }
        return ts;
    }

    /** Creates a list of TypeParameter from a list of maps.
     * @param typeParams The list of maps to create the TypeParameters.
     * @param container The declaration which owns the resulting type parameters.
     * @param existing A list of type parameters declared in the parent scopes which can be referenced from
     * the ones that have to be parsed. */
    private List<TypeParameter> parseTypeParameters(List<Map<String,Object>> typeParams, Declaration container,
            List<TypeParameter> existing) {
        if (typeParams == null) return null;
        List<TypeParameter> allparms = new ArrayList<TypeParameter>((existing == null ? 0 : existing.size()) + typeParams.size());
        if (existing != null && !existing.isEmpty()) {
            allparms.addAll(existing);
        }
        List<TypeParameter> tparms = new ArrayList<TypeParameter>(typeParams.size());
        for (Map<String,Object> tp : typeParams) {
            TypeParameter tparm = new TypeParameter();
            if (tp.containsKey(MetamodelGenerator.KEY_NAME)) {
                tparm.setName((String)tp.get(MetamodelGenerator.KEY_NAME));
                if (tp.containsKey(MetamodelGenerator.KEY_PACKAGE)) {
                    ProducedType subtype = getTypeFromJson(tp, allparms);
                    tparm.setExtendedType(subtype);
                } else {
                    tparm.setExtendedType(getTypeFromJson(voidclass, null));
                }
            } else if (tp.containsKey(MetamodelGenerator.KEY_TYPES)) {
                if (!("u".equals(tp.get("comp")) || "i".equals(tp.get("comp")))) {
                    throw new IllegalArgumentException("Only union or intersection types are allowed as 'comp'");
                }
                ProducedType subtype = getTypeFromJson(tp, allparms);
                tparm.setName(subtype.getProducedTypeName());
                tparm.setExtendedType(subtype);
            } else {
                throw new IllegalArgumentException("Invalid type parameter map " + tp);
            }
            String variance = (String)tp.get("variance");
            if ("out".equals(variance)) {
                tparm.setCovariant(true);
            } else if ("in".equals(variance)) {
                tparm.setContravariant(true);
            }
            tparm.setUnit(unit);
            tparm.setDeclaration(container);
            container.getMembers().add(tparm);
            if (container instanceof Scope) {
                tparm.setContainer((Scope)container);
            }
            tparm.setSequenced(tp.containsKey("seq"));
            tparms.add(tparm);
            allparms.add(tparm);
            if (tp.containsKey("satisfies")) {
                tparm.setSatisfiedTypes(parseTypeList((List<Map<String,Object>>)tp.get("satisfies"), allparms));
            } else if (tp.containsKey("of")) {
                tparm.setCaseTypes(parseTypeList((List<Map<String,Object>>)tp.get("of"), allparms));
            }
        }
        return tparms;
    }

    /** Creates a parameter list from a list of maps where each map represents a parameter.
     * @param The list of maps to create the parameters.
     * @param owner The declaration to assign to each parameter.
     * @param typeParameters The type parameters which can be referenced from the parameters. */
    private ParameterList parseParameters(List<Map<String,Object>> params, Declaration owner, List<TypeParameter> typeParameters) {
        ParameterList plist = new ParameterList();
        if (params != null) {
            for (Map<String,Object> p : params) {
                Parameter param = null;
                String paramtype = (String)p.get("$pt");
                if ("v".equals(paramtype)) {
                    param = new ValueParameter();
                } else if ("f".equals(paramtype)) {
                    param = new FunctionalParameter();
                } else {
                    throw new IllegalArgumentException("Unknown parameter type " + paramtype);
                }
                param.setName((String)p.get(MetamodelGenerator.KEY_NAME));
                param.setUnit(unit);
                param.setDeclaration(owner);
                owner.getMembers().add(param);
                if (p.get(MetamodelGenerator.KEY_TYPE) instanceof Map) {
                    param.setType(getTypeFromJson((Map<String,Object>)p.get(MetamodelGenerator.KEY_TYPE), typeParameters));
                } else {
                    //parameter type
                    for (TypeParameter tp : typeParameters) {
                        if (tp.getName().equals(p.get(MetamodelGenerator.KEY_TYPE))) {
                            param.setType(tp.getType());
                        }
                    }
                }
                plist.getParameters().add(param);
            }
        }
        return plist;
    }

    @SuppressWarnings("unchecked")
    private Method loadMethod(String name, Map<String, Object> m, Scope parent, final List<TypeParameter> existing) {
        Method md = new Method();
        md.setName(name);
        md.setContainer(parent);
        setDefaultSharedActualFormal(md, m);
        md.setUnit(unit);
        if (parent == this) {
            //Top-level declarations are directly added to the unit
            unit.addDeclaration(md);
        }
        final List<TypeParameter> tparms = parseTypeParameters(
                (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_PARAMS), md, existing);
        if (tparms != null) {
            md.setTypeParameters(tparms);
        }
        final List<TypeParameter> allparms = JsonPackage.merge(existing, tparms);
        md.setType(getTypeFromJson((Map<String,Object>)m.get(MetamodelGenerator.KEY_TYPE), allparms));
        md.addParameterList(parseParameters((List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_PARAMS),
                md, allparms));
        if (name.equals("setItem")) {
            System.out.println(md.getQualifiedNameString() + " con " + md.getMembers());
        } else if (name.equals("coalesce")) {
            System.out.println("coalesce tipo " + md.getType());
        }
        return md;
    }

    private MethodOrValue loadAttribute(String name, Map<String, Object> m, Scope parent,
            List<TypeParameter> typeParameters) {
        String metatype = (String)m.get(MetamodelGenerator.KEY_METATYPE);
        MethodOrValue d = MetamodelGenerator.METATYPE_GETTER.equals(metatype) ? new Getter() : new Value();
        d.setName(name);
        d.setContainer(parent);
        d.setUnit(unit);
        if (parent == this) {
            unit.addDeclaration(d);
        }
        setDefaultSharedActualFormal(d, m);
        if (m.containsKey("var")) {
            ((Value)d).setVariable(true);
        }
        d.setType(getTypeFromJson((Map<String,Object>)m.get(MetamodelGenerator.KEY_TYPE), typeParameters));
        return d;
    }

    @SuppressWarnings("unchecked")
    private Interface loadInterface(String name, Map<String, Object> m, Scope parent, final List<TypeParameter> existing) {
        //Check if it's been loaded first
        Declaration maybe = parent.getDirectMember(name, null);
        if (maybe instanceof Interface) {
            return (Interface)maybe;
        }
        //It hasn't been loaded, so create it
        Interface t = new Interface();
        t.setContainer(parent);
        t.setName(name);
        t.setUnit(unit);
        setDefaultSharedActualFormal(t, m);
        //All interfaces extend Object
        t.setExtendedType(getTypeFromJson(objclass, null));
        if (parent == this) {
            unit.addDeclaration(t);
        }
        final List<TypeParameter> tparms = parseTypeParameters(
                (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_PARAMS), t, existing);
        if (tparms != null) {
            t.setTypeParameters(tparms);
        }
        final List<TypeParameter> allparms = JsonPackage.merge(existing, tparms);
        if (m.containsKey(MetamodelGenerator.KEY_SELF_TYPE)) {
            t.setSelfType(getTypeFromJson((Map<String,Object>)m.get(MetamodelGenerator.KEY_SELF_TYPE), allparms));
        }
        if (m.containsKey("of")) {
            t.setCaseTypes(parseTypeList((List<Map<String,Object>>)m.get("of"), allparms));
        }
        if (m.containsKey("satisfies")) {
            t.setSatisfiedTypes(parseTypeList((List<Map<String,Object>>)m.get("satisfies"), allparms));
        }
        if (m.containsKey(MetamodelGenerator.KEY_INTERFACES)) {
            for (Map.Entry<String,Map<String,Object>> inner : ((Map<String,Map<String,Object>>)m.get(MetamodelGenerator.KEY_OBJECTS)).entrySet()) {
                loadInterface(inner.getKey(), inner.getValue(), t, allparms);
            }
        }
        if (m.containsKey(MetamodelGenerator.KEY_CLASSES)) {
            for (Map.Entry<String,Map<String,Object>> inner : ((Map<String,Map<String,Object>>)m.get(MetamodelGenerator.KEY_CLASSES)).entrySet()) {
                loadClass(inner.getKey(), inner.getValue(), t, allparms);
            }
        }
        if (m.containsKey(MetamodelGenerator.KEY_OBJECTS)) {
            for (Map.Entry<String,Map<String,Object>> inner : ((Map<String,Map<String,Object>>)m.get(MetamodelGenerator.KEY_OBJECTS)).entrySet()) {
                loadObject(inner.getKey(), inner.getValue(), t, allparms);
            }
        }
        addAttributesAndMethods(m, t, allparms);
        return t;
    }

    /** Loads an object declaration, creating it if necessary, and returns its type declaration. */
    @SuppressWarnings("unchecked")
    private TypeDeclaration loadObject(String name, Map<String, Object> m, Scope parent, List<TypeParameter> existing) {
        Declaration maybe = parent.getDirectMember(name, null);
        if (maybe instanceof Value) {
            return ((Value) maybe).getTypeDeclaration();
        }
        Value obj = new Value();
        obj.setName(name);
        obj.setContainer(parent);
        setDefaultSharedActualFormal(obj, m);
        com.redhat.ceylon.compiler.typechecker.model.Class type = new com.redhat.ceylon.compiler.typechecker.model.Class();
        type.setName(name);
        setDefaultSharedActualFormal(type, m);
        type.setAnonymous(true);
        type.setUnit(unit);
        type.setContainer(parent);
        if (parent == this) {
            unit.addDeclaration(obj);
            unit.addDeclaration(type);
        }
        if (m.containsKey("super")) {
            type.setExtendedType(getTypeFromJson((Map<String,Object>)m.get("super"), existing));
        } else {
            type.setExtendedType(getTypeFromJson(idobj, existing));
        }
        if (m.containsKey("satisfies")) {
            type.setSatisfiedTypes(parseTypeList((List<Map<String,Object>>)m.get("satisfies"), existing));
        }
        obj.setType(type.getType());
        return type;
    }

    /** Looks up a type from model data, creating it if necessary. The returned type will have its
     * type parameters substituted if needed. */
    private ProducedType getTypeFromJson(Map<String, Object> m, List<TypeParameter> typeParams) {
        ProducedType rval = null;
        if (m.containsKey("comp")) {
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> tmaps = (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPES);
            ArrayList<ProducedType> types = new ArrayList<ProducedType>(tmaps.size());
            for (Map<String, Object> tmap : tmaps) {
                types.add(getTypeFromJson(tmap, typeParams));
            }
            if ("u".equals(m.get("comp"))) {
                UnionType ut = new UnionType(unit);
                ut.setCaseTypes(types);
                rval = ut.getType();
            } else {
                IntersectionType it = new IntersectionType(unit);
                it.setSatisfiedTypes(types);
                rval = it.getType();
            }
        } else {
            String tname = (String)m.get(MetamodelGenerator.KEY_NAME);
            String pname = (String)m.get(MetamodelGenerator.KEY_PACKAGE);
            if (pname == null) {
                //Maybe it's a ref to a type parameter
                for (TypeParameter typeParam : typeParams) {
                    if (typeParam.getName().equals(tname)) {
                        return typeParam.getType();
                    }
                }
            }
            String mname = (String)m.get(MetamodelGenerator.KEY_MODULE);
            com.redhat.ceylon.compiler.typechecker.model.Package rp;
            if (mname == null) {
                //local type
                rp = getModule().getDirectPackage(pname);
            } else if ("ceylon.language".equals(mname)) {
                rp = getModule().getLanguageModule().getRootPackage();
            } else {
                rp = getModule().getPackage(pname);
            }
            for (Declaration d : rp.getMembers()) {
                if (d instanceof TypeDeclaration && tname.equals(d.getName())) {
                    //Type might me partially loaded so we add its type parameters here if needed
                    if (((TypeDeclaration)d).getTypeParameters().isEmpty() && m.containsKey(MetamodelGenerator.KEY_TYPE_PARAMS)) {
                        @SuppressWarnings("unchecked")
                        List<TypeParameter> tttparms = parseTypeParameters(
                                (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_PARAMS), d, typeParams);
                        ((TypeDeclaration)d).setTypeParameters(tttparms);
                    }
                    rval = ((TypeDeclaration)d).getType();
                }
            }
            if (rval == null && rp == this) {
                rval = ((TypeDeclaration)load(tname, typeParams)).getType();
            }
            if (rval != null && m.containsKey(MetamodelGenerator.KEY_TYPE_PARAMS)) {
                //Substitute type parameters
                HashMap<TypeParameter, ProducedType> concretes = new HashMap<TypeParameter, ProducedType>();
                Iterator<TypeParameter> viter = rval.getDeclaration().getTypeParameters().iterator();
                @SuppressWarnings("unchecked")
                List<Map<String,Object>> modelParms = (List<Map<String,Object>>)m.get(MetamodelGenerator.KEY_TYPE_PARAMS);
                if (modelParms.size() != rval.getDeclaration().getTypeParameters().size()) {
                    System.out.println("TODO!!! sequenced type arguments " + rval + " for " + rval.getDeclaration().getTypeParameters().size() + " vs " + modelParms);
                }
                TypeParameter _cparm = null;
                for (Map<String,Object> ptparm : modelParms) {
                    if (_cparm == null || !_cparm.isSequenced()) _cparm = viter.next();
                    if (ptparm.containsKey(MetamodelGenerator.KEY_PACKAGE) || ptparm.containsKey(MetamodelGenerator.KEY_TYPES)) {
                        //Substitute for proper type
                        concretes.put(_cparm, getTypeFromJson(ptparm, typeParams));
                    } else if (ptparm.containsKey(MetamodelGenerator.KEY_NAME)) {
                        //Look for type parameter with same name
                        for (TypeParameter typeParam : typeParams) {
                            if (typeParam.getName().equals(ptparm.get(MetamodelGenerator.KEY_NAME))) {
                                concretes.put(_cparm, typeParam.getType());
                            }
                        }
                    }
                }
                if (!concretes.isEmpty()) {
                    rval = rval.substitute(concretes);
                }
            }
        }
        if (rval == null) {
            System.out.println("couldn't find type " + m.get(MetamodelGenerator.KEY_PACKAGE) + "." + m.get(MetamodelGenerator.KEY_NAME) + " for " + m.get(MetamodelGenerator.KEY_MODULE));
        }
        return rval;
    }

    /** Load a top-level declaration with the specified name, by parsing its model data. */
    Declaration load(String name, List<TypeParameter> existing) {
        @SuppressWarnings("unchecked")
        Map<String,Object> map = (Map<String,Object>)model.get(name);
        if (map == null) {
            if ("Bottom".equals(name) && "ceylon.language".equals(pkgname)) {
                return bottom;
            }
            throw new IllegalStateException("Cannot find " + name + " in " + model.keySet());
        }
        String metatype = (String)map.get(MetamodelGenerator.KEY_METATYPE);
        if (metatype == null) {
            throw new IllegalArgumentException("Missing metatype from entry " + map);
        }
        if (metatype.equals(MetamodelGenerator.METATYPE_ATTRIBUTE)
                || metatype.equals(MetamodelGenerator.METATYPE_GETTER)) {
            return loadAttribute(name, map, this, null);
        } else if (metatype.equals(MetamodelGenerator.METATYPE_CLASS)) {
            return loadClass(name, map, this, existing);
        } else if (metatype.equals(MetamodelGenerator.METATYPE_INTERFACE)) {
            return loadInterface(name, map, this, existing);
        } else if (metatype.equals(MetamodelGenerator.METATYPE_METHOD)) {
            return loadMethod(name, map, this, existing);
        } else if (metatype.equals(MetamodelGenerator.METATYPE_OBJECT)) {
            return loadObject(name, map, this, existing);
        }
        System.out.println("WTF is this shit " + map);
        return null;
    }

    private void setDefaultSharedActualFormal(Declaration d, Map<String,Object> m) {
        d.setFormal(m.containsKey(MetamodelGenerator.ANN_FORMAL));
        d.setActual(m.containsKey(MetamodelGenerator.ANN_ACTUAL));
        d.setDefault(m.containsKey(MetamodelGenerator.ANN_DEFAULT));
        d.setShared(m.containsKey(MetamodelGenerator.ANN_SHARED));
    }

    /** Create a new list that contains the items in both lists. */
    public static <T> List<T> merge(List<T> l1, List<T> l2) {
        int size = (l1 == null ? 0 : l1.size()) + (l2 == null ? 0 : l2.size());
        ArrayList<T> merged = new ArrayList<T>(size);
        if (l1 != null) {
            merged.addAll(l1);
        }
        if (l2 != null) {
            merged.addAll(l2);
        }
        return merged;
    }

}
