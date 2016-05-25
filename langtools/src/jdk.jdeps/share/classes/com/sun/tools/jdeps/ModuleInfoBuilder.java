/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.tools.jdeps;

import static com.sun.tools.jdeps.JdepsTask.*;
import static com.sun.tools.jdeps.Analyzer.*;
import static com.sun.tools.jdeps.JdepsFilter.DEFAULT_FILTER;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class ModuleInfoBuilder {
    final JdepsConfiguration configuration;
    final Path outputdir;

    final DependencyFinder dependencyFinder;
    final Analyzer analyzer;

    // an input JAR file (loaded as an automatic module for analysis)
    // maps to an explicit module to generate module-info.java
    final Map<Module, Module> automaticToExplicitModule;
    public ModuleInfoBuilder(JdepsConfiguration configuration,
                             List<String> args,
                             Path outputdir) {
        this.configuration = configuration;
        this.outputdir = outputdir;

        this.dependencyFinder = new DependencyFinder(configuration, DEFAULT_FILTER);
        this.analyzer = new Analyzer(configuration, Type.CLASS, DEFAULT_FILTER);

        // add targets to modulepath if it has module-info.class
        List<Path> paths = args.stream()
            .map(fn -> Paths.get(fn))
            .collect(Collectors.toList());

        // automatic module to convert to explicit module
        this.automaticToExplicitModule = ModuleFinder.of(paths.toArray(new Path[0]))
                .findAll().stream()
                .map(configuration::toModule)
                .collect(Collectors.toMap(Function.identity(), Function.identity()));

        Optional<Module> om = automaticToExplicitModule.keySet().stream()
                                    .filter(m -> !m.descriptor().isAutomatic())
                                    .findAny();
        if (om.isPresent()) {
            throw new UncheckedBadArgs(new BadArgs("err.genmoduleinfo.not.jarfile",
                                                   om.get().getPathName()));
        }
        if (automaticToExplicitModule.isEmpty()) {
            throw new UncheckedBadArgs(new BadArgs("err.invalid.path", args));
        }
    }

    public boolean run() throws IOException {
        try {
            // pass 1: find API dependencies
            Map<Archive, Set<Archive>> requiresPublic = computeRequiresPublic();

            // pass 2: analyze all class dependences
            dependencyFinder.parse(automaticModules().stream());

            analyzer.run(automaticModules(), dependencyFinder.locationToArchive());

            boolean missingDeps = false;
            for (Module m : automaticModules()) {
                Set<Archive> apiDeps = requiresPublic.containsKey(m)
                                            ? requiresPublic.get(m)
                                            : Collections.emptySet();

                Path file = outputdir.resolve(m.name()).resolve("module-info.java");

                // computes requires and requires public
                Module explicitModule = toExplicitModule(m, apiDeps);
                if (explicitModule != null) {
                    automaticToExplicitModule.put(m, explicitModule);

                    // generate module-info.java
                    System.out.format("writing to %s%n", file);
                    writeModuleInfo(file,  explicitModule.descriptor());
                } else {
                    // find missing dependences
                    System.out.format("Missing dependence: %s not generated%n", file);
                    missingDeps = true;
                }
            }

            return !missingDeps;
        } finally {
            dependencyFinder.shutdown();
        }
    }

    boolean notFound(Archive m) {
        return m == NOT_FOUND || m == REMOVED_JDK_INTERNALS;
    }

    private Module toExplicitModule(Module module, Set<Archive> requiresPublic)
        throws IOException
    {
        // done analysis
        module.close();

        if (analyzer.requires(module).anyMatch(this::notFound)) {
            // missing dependencies
            return null;
        }

        Map<String, Boolean> requires = new HashMap<>();
        requiresPublic.stream()
            .map(Archive::getModule)
            .forEach(m -> requires.put(m.name(), Boolean.TRUE));

        analyzer.requires(module)
            .map(Archive::getModule)
            .forEach(d -> requires.putIfAbsent(d.name(), Boolean.FALSE));

        return module.toStrictModule(requires);
    }

    /**
     * Returns the stream of resulting modules
     */
    Stream<Module> modules() {
        return automaticToExplicitModule.values().stream();
    }

    /**
     * Returns the stream of resulting ModuleDescriptors
     */
    public Stream<ModuleDescriptor> descriptors() {
        return automaticToExplicitModule.entrySet().stream()
                    .map(Map.Entry::getValue)
                    .map(Module::descriptor);
    }

    void visitMissingDeps(Analyzer.Visitor visitor) {
        automaticModules().stream()
            .filter(m -> analyzer.requires(m).anyMatch(this::notFound))
            .forEach(m -> {
                analyzer.visitDependences(m, visitor, Analyzer.Type.VERBOSE);
            });
    }

    void writeModuleInfo(Path file, ModuleDescriptor descriptor) {
        try {
            Files.createDirectories(file.getParent());
            try (PrintWriter pw = new PrintWriter(Files.newOutputStream(file))) {
                printModuleInfo(pw, descriptor);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void printModuleInfo(PrintWriter writer, ModuleDescriptor descriptor) {
        writer.format("module %s {%n", descriptor.name());

        Map<String, Module> modules = configuration.getModules();
        // first print the JDK modules
        descriptor.requires().stream()
                  .filter(req -> !req.name().equals("java.base"))   // implicit requires
                  .sorted(Comparator.comparing(Requires::name))
                  .forEach(req -> writer.format("    requires %s;%n", req));

        descriptor.exports().stream()
                  .peek(exp -> {
                      if (exp.targets().size() > 0)
                          throw new InternalError(descriptor.name() + " qualified exports: " + exp);
                  })
                  .sorted(Comparator.comparing(Exports::source))
                  .forEach(exp -> writer.format("    exports %s;%n", exp.source()));

        descriptor.provides().values().stream()
                    .sorted(Comparator.comparing(Provides::service))
                    .forEach(p -> p.providers().stream()
                        .sorted()
                        .forEach(impl -> writer.format("    provides %s with %s;%n", p.service(), impl)));

        writer.println("}");
    }


    private Set<Module> automaticModules() {
        return automaticToExplicitModule.keySet();
    }

    /**
     * Compute 'requires public' dependences by analyzing API dependencies
     */
    private Map<Archive, Set<Archive>> computeRequiresPublic() throws IOException {
        // parse the input modules
        dependencyFinder.parseExportedAPIs(automaticModules().stream());

        return dependencyFinder.dependences();
    }
}
