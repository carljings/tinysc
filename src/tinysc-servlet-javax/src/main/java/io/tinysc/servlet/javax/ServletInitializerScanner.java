package io.tinysc.servlet.javax;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.annotation.HandlesTypes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class ServletInitializerScanner {
    private final Path webRoot;
    private final ClassLoader classLoader;

    ServletInitializerScanner(Path webRoot, ClassLoader classLoader) {
        this.webRoot = webRoot;
        this.classLoader = classLoader;
    }

    List<Match> scan() throws IOException {
        List<ServletContainerInitializer> initializers =
                new ArrayList<ServletContainerInitializer>();
        boolean metadataRequired = false;
        for (ServletContainerInitializer initializer
                : ServiceLoader.load(ServletContainerInitializer.class, classLoader)) {
            initializers.add(initializer);
            HandlesTypes handlesTypes = initializer.getClass().getAnnotation(HandlesTypes.class);
            metadataRequired |= handlesTypes != null && handlesTypes.value().length > 0;
        }
        if (initializers.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(initializers, new Comparator<ServletContainerInitializer>() {
            @Override
            public int compare(ServletContainerInitializer left,
                               ServletContainerInitializer right) {
                return left.getClass().getName().compareTo(right.getClass().getName());
            }
        });
        Map<String, ClassMetadata> metadata = metadataRequired
                ? scanClassMetadata() : Collections.<String, ClassMetadata>emptyMap();
        List<Match> result = new ArrayList<Match>();
        for (ServletContainerInitializer initializer : initializers) {
            HandlesTypes handlesTypes = initializer.getClass().getAnnotation(HandlesTypes.class);
            Set<Class<?>> matches = handlesTypes == null
                    ? Collections.<Class<?>>emptySet()
                    : findHandledTypes(handlesTypes.value(), metadata);
            result.add(new Match(initializer, matches));
        }
        return result;
    }

    private Map<String, ClassMetadata> scanClassMetadata() throws IOException {
        final Map<String, ClassMetadata> result = new LinkedHashMap<String, ClassMetadata>();
        Path classes = webRoot.resolve("WEB-INF").resolve("classes");
        if (Files.isDirectory(classes)) {
            Files.walkFileTree(classes, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    if (file.getFileName().toString().endsWith(".class")) {
                        try (InputStream input = Files.newInputStream(file)) {
                            addMetadata(input, result);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Path libraries = webRoot.resolve("WEB-INF").resolve("lib");
        if (Files.isDirectory(libraries)) {
            List<Path> jars = new ArrayList<Path>();
            try (java.nio.file.DirectoryStream<Path> stream =
                         Files.newDirectoryStream(libraries, "*.jar")) {
                for (Path jar : stream) {
                    jars.add(jar);
                }
            }
            Collections.sort(jars);
            for (Path jarPath : jars) {
                try (JarFile jar = new JarFile(jarPath.toFile())) {
                    java.util.Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!entry.isDirectory() && name.endsWith(".class")
                                && !name.startsWith("META-INF/versions/")) {
                            try (InputStream input = jar.getInputStream(entry)) {
                                addMetadata(input, result);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private Set<Class<?>> findHandledTypes(Class<?>[] handledTypes,
                                            Map<String, ClassMetadata> metadata) {
        Set<String> matchingNames = new LinkedHashSet<String>();
        for (Class<?> handledType : handledTypes) {
            String target = Type.getInternalName(handledType);
            if (handledType.isAnnotation()) {
                String descriptor = Type.getDescriptor(handledType);
                for (ClassMetadata candidate : metadata.values()) {
                    if (candidate.annotations.contains(descriptor)) {
                        matchingNames.add(candidate.name);
                    }
                }
            } else {
                Map<String, Boolean> memo = new HashMap<String, Boolean>();
                for (ClassMetadata candidate : metadata.values()) {
                    if (!candidate.name.equals(target)
                            && isSubtype(candidate.name, target, metadata, memo,
                            new HashSet<String>())) {
                        matchingNames.add(candidate.name);
                    }
                }
            }
        }
        List<String> orderedNames = new ArrayList<String>(matchingNames);
        Collections.sort(orderedNames);
        Set<Class<?>> result = new LinkedHashSet<Class<?>>();
        for (String internalName : orderedNames) {
            try {
                result.add(Class.forName(internalName.replace('/', '.'), false, classLoader));
            } catch (ClassNotFoundException ignored) {
                // The class path changed after scanning; the initializer simply cannot use it.
            } catch (LinkageError ignored) {
                // Optional application classes may refer to dependencies not present at runtime.
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean isSubtype(String candidate, String target,
                                     Map<String, ClassMetadata> metadata,
                                     Map<String, Boolean> memo, Set<String> visiting) {
        if (candidate == null) {
            return false;
        }
        if (candidate.equals(target)) {
            return true;
        }
        Boolean cached = memo.get(candidate);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(candidate)) {
            return false;
        }
        ClassMetadata value = metadata.get(candidate);
        boolean matches = false;
        if (value != null) {
            matches = isSubtype(value.superName, target, metadata, memo, visiting);
            if (!matches) {
                for (String interfaceName : value.interfaces) {
                    if (isSubtype(interfaceName, target, metadata, memo, visiting)) {
                        matches = true;
                        break;
                    }
                }
            }
        }
        visiting.remove(candidate);
        memo.put(candidate, matches);
        return matches;
    }

    private static void addMetadata(InputStream input, final Map<String, ClassMetadata> target)
            throws IOException {
        ClassReader reader = new ClassReader(input);
        final ClassMetadata metadata = new ClassMetadata();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                metadata.name = name;
                metadata.superName = superName;
                if (interfaces != null) {
                    Collections.addAll(metadata.interfaces, interfaces);
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                metadata.annotations.add(descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (metadata.name != null && !target.containsKey(metadata.name)) {
            target.put(metadata.name, metadata);
        }
    }

    static final class Match {
        private final ServletContainerInitializer initializer;
        private final Set<Class<?>> handledTypes;

        private Match(ServletContainerInitializer initializer, Set<Class<?>> handledTypes) {
            this.initializer = initializer;
            this.handledTypes = handledTypes;
        }

        ServletContainerInitializer initializer() {
            return initializer;
        }

        Set<Class<?>> handledTypes() {
            return handledTypes;
        }
    }

    private static final class ClassMetadata {
        private String name;
        private String superName;
        private final List<String> interfaces = new ArrayList<String>();
        private final Set<String> annotations = new LinkedHashSet<String>();
    }
}
