package com.redhat.ceylon.compiler.js;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.redhat.ceylon.compiler.typechecker.model.ClassOrInterface;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.MethodOrValue;
import com.redhat.ceylon.compiler.typechecker.model.Module;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.Scope;
import com.redhat.ceylon.compiler.typechecker.model.TypeAlias;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;

/**
 * Manages the identifier names in the JavaScript code generated for a Ceylon
 * compilation unit.
 *
 * @author Ivo Kasiuk
 */
public class JsIdentifierNames {

    private static long uniqueID = 0;
    private static long nextUID() {
        if (++uniqueID <= 0) {
            uniqueID = 1;
        }
        return uniqueID;
    }

    private static Set<String> reservedWords = new HashSet<String>();

    static {
        // Identifiers that have to be escaped because they are keywords in
        // JavaScript. We don't have to include identifiers that are also
        // keywords in Ceylon because no such identifiers can occur in Ceylon
        // source code anyway.
        //Language
        reservedWords.addAll(Arrays.asList("undefined", "boolean", "byte", "char", "const",
                "debugger", "default", "delete", "do", "double", "enum", "export", "false",
                "final", "float", "goto", "implements", "instanceof", "int", "long",
                "native", "new", "null", "private", "protected", "public", "short", "static",
                "synchronized", "throws", "transient", "true", "typeof", "var", "volatile",
                "with"));
        //Types
        reservedWords.addAll(Arrays.asList("Date", "Object", "Boolean", "Error", "Number"));
        //JS Object
        reservedWords.addAll(Arrays.asList("hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable"));
        //JS Function
        reservedWords.add("Function");
        reservedWords.addAll(Arrays.asList("call", "arguments", "caller", "apply", "bind")); //name?
        //JS Number
        reservedWords.addAll(Arrays.asList("toFixed", "valueOf", "toPrecision", "toExponential"));
        //JS String
        reservedWords.add("String");
        reservedWords.addAll(Arrays.asList("charAt", "strike", "fixed", "sub", "charCodeAt",
                "trimLeft", "toLocaleUpperCase", "toUpperCase", "fontsize", "search",
                "toLocaleLowerCase", "small", "big", "fontcolor", "blink", "trim",
                "bold", "match", "substr", "trimRight", "replace", "split", "sup", "link",
                "localeCompare", "valueOf", "substring", "toLowerCase", "italics", "anchor"));
        //JS Array
        reservedWords.add("Array");
        reservedWords.addAll(Arrays.asList("toLocaleString", "splice", "map", "forEach", "reverse",
                "join", "push", "shift", "pop", "sort", "unshift", "reduceRight", "reduce",
                "every", "filter"));
        //String, Array, etc
        reservedWords.addAll(Arrays.asList("length", "toString", "constructor", "prototype",
                "concat", "indexOf", "lastIndexOf", "slice", "get"));
    }

    /**
     * Determine the identifier name to be used in the generated JavaScript code
     * to represent the given declaration.
     */
    public String name(Declaration decl) {
        return getName(decl, false, false);
    }

    public String name(Parameter param) {
        if (param == null) { return null; }
        String name = param.getName();
        MethodOrValue decl = param.getModel();
        final boolean nonLocal = (decl.isShared() && decl.isMember())
                || (decl.isToplevel() && decl instanceof Method);
        if (nonLocal) {
            // The identifier might be accessed from other .js files, so it must
            // be reliably reproducible. In most cases simply using the original
            // name is ok because otherwise it would result in a name collision in
            // Ceylon too. We just have to take care of a few exceptions:
            String suffix = nestingSuffix(decl);
            if (suffix.length() > 0) {
                // nested type
                name += suffix;
            } else if (reservedWords.contains(name)) {
                // JavaScript keyword
                name = "$_" + name;
            }
        }
        else {
            // The identifier will not be used outside the generated .js file,
            // so we can simply disambiguate it with a numeric ID.
            name = uniqueVarNames.get(decl);
            if (name == null) {
                name = "$" + Long.toString(getUID(decl), 36);
            }
        }
        return name;
    }

    /**
     * Determine a secondary, private identifier name for the given declaration.
     */
    public String privateName(Declaration decl) {
        return getName(decl, false, true);
    }

    /**
     * Determine the function name to be used in the generated JavaScript code
     * for the getter of the given declaration.
     */
    public String getter(Declaration decl) {
        if (decl == null) { return ""; }
        String name = getName(decl, true, false);
        return String.format("get%c%s", Character.toUpperCase(name.charAt(0)),
                name.substring(1));
    }

    /**
     * Determine the function name to be used in the generated JavaScript code
     * for the setter of the given declaration.
     */
    public String setter(Declaration decl) {
        String name = getName(decl, true, false);
        return String.format("set%c%s", Character.toUpperCase(name.charAt(0)),
                name.substring(1));
    }

    /**
     * Determine the identifier to be used in the generated JavaScript code as
     * an alias for the given package.
     */
    public String moduleAlias(Module pkg) {
        if (JsCompiler.isCompilingLanguageModule() && pkg.getLanguageModule()==pkg) {
            //If we're compiling the language module, omit the package name
            return "";
        }
        StringBuilder sb = new StringBuilder("m$");
        sb.append(Long.toString(getUID(pkg), 36));
        return sb.toString();
    }

    /** Creates a new unique identifier. */
    public String createTempVariable() {
        return "$"+Long.toString(nextUID(),36);
    }

    /**
     * Determine the identifier to be used for the self variable of the given type.
     */
    public String self(TypeDeclaration decl) {
        String name = decl.getName();
        if (decl.isShared() || decl.isToplevel()) {
            name += nestingSuffix(decl);
        } else {
            // The identifier will not be used outside the generated .js file,
            // so we can simply disambiguate it with a numeric ID.
            name = "$" + Long.toString(getUID(decl), 36);
        }
        return String.format("%c%s$", Character.toLowerCase(name.charAt(0)),
                    name.substring(1));
    }

    /**
     * Returns a disambiguation suffix for the given scope. It is guaranteed that
     * the suffixes generated for two different scopes are different.
     */
    public String scopeSuffix(Scope scope) {
        return String.format("$%s", scope.getQualifiedNameString().replace("::","$").replace('.', '$'));
    }

    /**
     * Generates a disambiguation suffix if the given declaration is a nested
     * declaration whose name may collide with other declarations.
     * Currently this is required only for types which are nested inside other
     * types.
     */
    private String nestingSuffix(Declaration decl) {
        String suffix = "";
        if (decl instanceof TypeDeclaration) {
            // The generated suffix consists of the names of the enclosing types.
            StringBuilder sb = new StringBuilder();
            // Use the original declaration if it's an overriden class: an overriding
            // member must have the same name as the member it overrides.
            Scope scope = originalDeclaration(decl).getContainer();
            while (scope instanceof TypeDeclaration) {
                sb.append('$');
                sb.append(((TypeDeclaration) scope).getName());
                scope = scope.getContainer();
            }
            suffix = sb.toString();
        }
        return suffix;
    }

    public void forceName(Declaration decl, String name) {
        uniqueVarNames.put(decl, name);
    }

    private Map<Module, Long> moduleUIDs = new HashMap<Module, Long>();
    private Map<Declaration, Long> uniqueVarIDs = new HashMap<Declaration, Long>();
    private Map<Declaration, String> uniqueVarNames =
            new HashMap<Declaration, String>();

    private String getName(Declaration decl, boolean forGetterSetter, boolean priv) {
        if (decl == null) { return null; }
        String name = decl.getName();
        boolean nonLocal = !priv;
        if (nonLocal) {
            // check if it's a shared member or a toplevel function
            nonLocal = decl.isMember() ? decl.isShared() || decl instanceof TypeDeclaration :
                decl.isToplevel() && (forGetterSetter || (decl instanceof Method)
                || (decl instanceof ClassOrInterface) || (decl instanceof TypeAlias));
        }
        if (nonLocal && decl instanceof com.redhat.ceylon.compiler.typechecker.model.Class
                && ((com.redhat.ceylon.compiler.typechecker.model.Class)decl).isAnonymous() && !forGetterSetter) {
            // A lower-case class name belongs to an object and is not public.
            nonLocal = false;
        }
        if (nonLocal) {
            // The identifier might be accessed from other .js files, so it must
            // be reliably reproducible. In most cases simply using the original
            // name is ok because otherwise it would result in a name collision in
            // Ceylon too. We just have to take care of a few exceptions:
            String suffix = nestingSuffix(decl);
            if (suffix.length() > 0) {
                // nested type
                name += suffix;
            } else if (!forGetterSetter && reservedWords.contains(name)) {
                // JavaScript keyword
                name = "$_" + name;
            }
        }
        else {
            // The identifier will not be used outside the generated .js file,
            // so we can simply disambiguate it with a numeric ID.
            name = uniqueVarNames.get(decl);
            if (name == null) {
                name = String.format(priv ? "$%s_" : "$%s", Long.toString(getUID(decl), 36));
            }
        }
        //Fix #204 - same top-level declarations in different packages
        if (decl.isToplevel() && !decl.getUnit().getPackage().equals(decl.getUnit().getPackage().getModule().getRootPackage())) {
            //rootPackage can be null when compiling from IDE
            String rootName = decl.getUnit().getPackage().getModule().getRootPackage() == null ?
                    "":decl.getUnit().getPackage().getModule().getRootPackage().getNameAsString();
            String pkgName = decl.getUnit().getPackage().getNameAsString();
            rootName = pkgName.substring(rootName.length()).replaceAll("\\.", "\\$");
            if (rootName.length()>0 && rootName.charAt(0) != '$') {
                rootName = '$' + rootName;
            }
            name += rootName;
        }
        if (decl instanceof TypeAlias) {
            name+="()";
        }
        return name;
    }
    
    private Declaration originalDeclaration(Declaration decl) {
        Declaration refinedDecl = decl;
        while (true) {
            Declaration d = refinedDecl.getRefinedDeclaration();
            if ((d == null) || (d == refinedDecl)) { break; }
            refinedDecl = d;
        }
        return refinedDecl;
    }

    private long getUID(Declaration decl) {
        Long id = uniqueVarIDs.get(decl);
        if (id == null) {
            id = nextUID();
            uniqueVarIDs.put(decl, id);
        }
        return id;
    }

    private long getUID(Module pkg) {
        Long id = moduleUIDs.get(pkg);
        if (id == null) {
            id = nextUID();
            moduleUIDs.put(pkg, id);
        }
        return id;
    }

}
