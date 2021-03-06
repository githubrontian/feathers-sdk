/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package flex2.tools;

import flash.swf.tags.DefineFont;
import flash.swf.tags.DefineTag;
import flash.util.FileUtils;
import flash.util.StringJoiner;
import flash.util.Trace;
import flex2.compiler.*;
import flex2.compiler.abc.AbcClass;
import flex2.compiler.as3.binding.ClassInfo;
import flex2.compiler.as3.binding.TypeAnalyzer;
import flex2.compiler.common.CompilerConfiguration;
import flex2.compiler.common.Configuration;
import flex2.compiler.common.FramesConfiguration;
import flex2.compiler.common.MxmlConfiguration;
import flex2.compiler.common.Configuration.RslPathInfo;
import flex2.compiler.common.FramesConfiguration.FrameInfo;
import flex2.compiler.extensions.ExtensionManager;
import flex2.compiler.extensions.IPreLinkExtension;
import flex2.compiler.i18n.I18nUtils;
import flex2.compiler.io.FileUtil;
import flex2.compiler.io.TextFile;
import flex2.compiler.io.VirtualFile;
import flex2.compiler.mxml.lang.StandardDefs;
import flex2.compiler.swc.Swc;
import flex2.compiler.swc.SwcScript;
import flex2.compiler.util.CompilerMessage;
import flex2.compiler.util.MimeMappings;
import flex2.compiler.util.Name;
import flex2.compiler.util.NameFormatter;
import flex2.compiler.util.NameMappings;
import flex2.compiler.util.QName;
import flex2.compiler.util.ThreadLocalToolkit;
import flex2.compiler.util.VelocityException;
import flex2.linker.CULinkable;
import flex2.linker.DependencyWalker.LinkState;
import flex2.linker.LinkerException;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * A flex2.compiler.PreLink implementation, which creates the FlexInit
 * and SystemManager subclass.
 *
 * @author Clement Wong
 * @author Roger Gonzalez (mixin, flexinit, bootstrap)
 * @author Basil Hosmer (service config)
 * @author Brian Deitte (font)
 * @author Cathy Murphy (accessibility)
 * @author Gordon Smith (i18n)
 */
public class PreLink implements flex2.compiler.PreLink
{
    public boolean run(List<Source> sources, List<CompilationUnit> units,
                    FileSpec fileSpec, SourceList sourceList, SourcePath sourcePath, ResourceBundlePath bundlePath,
                    ResourceContainer resources, SymbolTable symbolTable, CompilerSwcContext swcContext,
                    NameMappings nameMappings, Configuration configuration)
    {
        int initialSourceCount = sources.size();

        Set<IPreLinkExtension> extensions = 
            ExtensionManager.getPreLinkExtensions( configuration.getCompilerConfiguration().getExtensionsConfiguration().getExtensionMappings() );
        for ( IPreLinkExtension extension : extensions )
        {
            if(ThreadLocalToolkit.errorCount() == 0) {
                extension.run( sources, units, fileSpec, sourceList, sourcePath, bundlePath, resources, symbolTable,
                                  swcContext, configuration );
            }
        }
        boolean reRunPrelink = processMainUnit(sources, units, resources, symbolTable, nameMappings, configuration);

        // Check if additional sources were generated after processing the
        // main compilation unit. The compiler will need to re-run pre-link.
        if (sources.size() > initialSourceCount || reRunPrelink)
        {
        	return true;
        }

        return false;
    }

    /**
     * generate sources for units which require complete set of original units as type context
     */
    public void postRun(List<Source> sources, List<CompilationUnit> units,
                        ResourceContainer resources,
                        SymbolTable symbolTable,
                        CompilerSwcContext swcContext,
                        NameMappings nameMappings,
                        Configuration configuration)
    {
        LinkedList<Source> extraSources = new LinkedList<Source>();
        LinkedList<String> mixins = new LinkedList<String>();
        LinkedList<DefineTag> fonts = new LinkedList<DefineTag>();
        Set<String> contributingSwcs = new HashSet<String>();
        
        // Build a set, such as { "core", "controls" }, of the names
        // of all resource bundles used in all compilation units.
        // This will be used to set the compiledResourceBundleNames
        // property of the module factory's info() Object
        // and the compileResourceBundleNames property
        // of the _CompileResourceBundleInfo class.
        processResourceBundleNames(units, configuration);

        // TODO - factor out the unit iteration / list discovery to a more clear separate step

        // Autogenerate the _MyApp_FlexInit class.
        processInitClass(units, configuration, extraSources, mixins, fonts, contributingSwcs, swcContext);

        // Autogenerate the _MyApp_mx_managers_SystemManager class.
        boolean generatedLoaderClass = processLoaderClass(units, configuration, extraSources, 
                                                          mixins, fonts, contributingSwcs, 
                                                          swcContext);

        // Autogenerate the _CompiledResourceBundleInfo class. this allows a
        // resource manager to automatically detect the compiled resource
        // bundles.
        // Flex used to skip this step if a loader class was generated, but
        // Feathers will not require the use of a resource manager.
        processCompiledResourceBundleInfoClass(units, configuration, extraSources, mixins, fonts, swcContext);

        // Add the autogenerated sources to the ResourceContainer, so
        // they can be resolved in subsequent incremental compilations,
        // where the main unit doesn't require recompilation.
        for (Source extraSource : extraSources)
        {
            sources.add(resources.addResource(extraSource));
        }

        CompilerConfiguration compilerConfiguration = configuration.getCompilerConfiguration();
        int compatibilityVersion = compilerConfiguration.getCompatibilityVersion();

        StandardDefs standardDefs = ThreadLocalToolkit.getStandardDefs();
        TypeAnalyzer typeAnalyzer = symbolTable.getTypeAnalyzer();
        assert(typeAnalyzer != null);
        
        for (int i = 0, length = units.size(); i < length; i++)
        {
            CompilationUnit u = (CompilationUnit) units.get(i);

            if (u.isRoot())
            {
                Source source = u.getSource();

                // Now that we're done validating the StyleContainer,
                // we can disconnect the root's logger.
                source.disconnectLogger();

                // Clear the root's path resolver, so the
                // CompilationUnit doesn't hold onto the global path
                // resolver, which has strong references to things
                // like the SourcePath, which we want freed up at the
                // end of a compilation.  All the other
                // CompilationUnit's have their PathResolver cleared
                // when they reach the ABC state.  We hold onto the
                // root's path resolver until now, because of the
                // styles
                source.setPathResolver(null);
            }
            else if (generatedLoaderClass && !u.getSource().isInternal() && !u.getSource().isSwcScriptOwner())
            {
                // Check if the source is a module or an application. If it is and we know it 
                // is not the root then generate a warning.
                if (typeAnalyzer != null)
                {
                    for (QName qName : u.topLevelDefinitions)
                    {
                        ClassInfo info = typeAnalyzer.getClassInfo(qName.toString());
                        checkForModuleOrApplication(standardDefs, typeAnalyzer, info, qName, configuration);
                    }
                }
            }
            
            // Only check the dependencies of hand written compilation units.
            if (!u.getSource().isSwcScriptOwner() && compilerConfiguration.enableSwcVersionFiltering())
            {
                Set<Name> dependencies = new HashSet<Name>();
                dependencies.addAll(u.inheritance);
                dependencies.addAll(u.namespaces);
                dependencies.addAll(u.expressions);
                dependencies.addAll(u.types);

                for (Name name : dependencies)
                {
                    if (name instanceof QName)
                    {
                        Source dependent = symbolTable.findSourceByQName((QName) name);

                        if (dependent.isSwcScriptOwner())
                        {
                            SwcScript swcScript = (SwcScript) dependent.getOwner();
                            Swc swc = swcScript.getLibrary().getSwc();
                        
                            // Make sure each dependency's minimum
                            // supported version is less than or equal to
                            // the compatibility version.
                            if (compatibilityVersion < swc.getVersions().getMinimumVersion())
                            {
                                DependencyNotCompatible message =
                                    new DependencyNotCompatible(swcScript.getName().replace('/', '.'),
                                                                swc.getLocation(),
                                                                swc.getVersions().getMinimumVersionString(),
                                                                compilerConfiguration.getCompatibilityVersionString());
                                ThreadLocalToolkit.log(message, u.getSource());
                            }
                        }
                    }
                }
            }
        }
    }

    
    /**
     * Determine if the class is a module or an application.
     * 
     * @param standardDefs
     * @param typeAnalyzer
     * @param info - ClassInfo of the class being check.
     * @param root - CompilationUnit of the root, may not be null.
     * @param qName - qName of the class being checked.
     * @param configuration
     */
    private void checkForModuleOrApplication(StandardDefs standardDefs, TypeAnalyzer typeAnalyzer,
                                        ClassInfo info, QName qName, Configuration configuration)
    {
        if (info != null)
        {
            // Does the class implement IModule or extend one of the Application classes.
            if (info.implementsInterface(standardDefs.getModulesPackage(), 
                                         StandardDefs.INTERFACE_IMODULE_NO_PACKAGE) ||
                info.extendsClass(StandardDefs.CLASS_APPLICATION) ||
                info.extendsClass(StandardDefs.CLASS_SPARK_APPLICATION))
            {
                // Now test that the root does not extend or implement this class before we generate a
                // warning.
                ClassInfo rootInfo = typeAnalyzer.getClassInfo(configuration.getMainDefinition());
                if (rootInfo != null &&
                    !rootInfo.implementsInterface(qName.getNamespace(), qName.getLocalPart()) &&
                    !rootInfo.extendsClass(qName.toString()))
                {
                    ThreadLocalToolkit.getLogger().log(new CompiledAsAComponent(qName.toString(), 
                                                       configuration.getMainDefinition()));
                }
            }
        }
        
    }

    private boolean processMainUnit(List<Source> sources, List<CompilationUnit> units, ResourceContainer resources,
                                 SymbolTable symbolTable, NameMappings nameMappings, Configuration configuration)
    {
        boolean generatedSources = false;

        for (int i = 0, length = units.size(); i < length; i++)
        {
            CompilationUnit u = (CompilationUnit) units.get(i);

            if (u.isRoot())
            {
                swfmetadata(u, configuration);

                if (u.loaderClass != null)
                {
                    configuration.setRootClassName(u.loaderClass);
                }

                // set the last top level definition as the main definition.  Setting the last one allows
                // for Embed classes at the top of the file
                QName qName = u.topLevelDefinitions.last();

                if (qName != null)
                {
                    String def = qName.toString();
                    configuration.setMainDefinition(def);
                    u.getContext().setAttribute("mainDefinition", def);

					// i.e. isApplication... need loader class for styles.
					// We also need styles for an AS file that subclasses Application or Module.
                    if (u.loaderClass != null || u.loaderClassBase != null)
                    {
                        CompilerConfiguration compilerConfig = configuration.getCompilerConfiguration();
                        
                        List<CULinkable> linkables = new LinkedList<CULinkable>();

                        for (Iterator it2 = units.iterator(); it2.hasNext();)
                        {
                            linkables.add( new CULinkable( (CompilationUnit) it2.next() ) );
                        }
                    }
                }
                else
                {
                    ThreadLocalToolkit.log(new NoExternalVisibleDefinition(), u.getSource());
                }

                break;
            }
        }

        return generatedSources;
    }

    private static void swfmetadata(CompilationUnit u, Configuration cfg)
    {
        if (u.swfMetaData != null)
        {
            String widthString = u.swfMetaData.getValue("width");
            if (widthString != null)
            {
                cfg.setWidth(widthString);
            }

            String heightString = u.swfMetaData.getValue("height");
            if (heightString != null)
            {
                cfg.setHeight(heightString);
            }

            String widthPercent = u.swfMetaData.getValue("widthPercent");
            if (widthPercent != null)
            {
                cfg.setWidthPercent(widthPercent);
            }

            String heightPercent = u.swfMetaData.getValue("heightPercent");
            if (heightPercent != null)
            {
                cfg.setHeightPercent(heightPercent);
            }

            String scriptRecursionLimit = u.swfMetaData.getValue("scriptRecursionLimit");
            if (scriptRecursionLimit != null)
            {
                try
                {
                    cfg.setScriptRecursionLimit(Integer.parseInt(scriptRecursionLimit));
                }
                catch (NumberFormatException nfe)
                {
                    ThreadLocalToolkit.log(new CouldNotParseNumber(scriptRecursionLimit, "scriptRecursionLimit"));
                }
            }

            String scriptTimeLimit = u.swfMetaData.getValue("scriptTimeLimit");
            if (scriptTimeLimit != null)
            {
                try
                {
                    cfg.setScriptTimeLimit(Integer.parseInt(scriptTimeLimit));
                }
                catch (NumberFormatException nfe)
                {
                    ThreadLocalToolkit.log(new CouldNotParseNumber(scriptTimeLimit, "scriptTimeLimit"));
                }
            }

            String frameRate = u.swfMetaData.getValue("frameRate");
            if (frameRate != null)
            {
                try
                {
                    cfg.setFrameRate(Integer.parseInt(frameRate));
                }
                catch (NumberFormatException nfe)
                {
                    ThreadLocalToolkit.log(new CouldNotParseNumber(frameRate, "frameRate"));
                }
            }

            String backgroundColor = u.swfMetaData.getValue("backgroundColor");
            if (backgroundColor != null)
            {
                try
                {
                    cfg.setBackgroundColor(Integer.decode(backgroundColor).intValue());
                }
                catch (NumberFormatException numberFormatException)
                {
                    ThreadLocalToolkit.log(new InvalidBackgroundColor(backgroundColor), u.getSource());
                }
            }

            String pageTitle = u.swfMetaData.getValue("pageTitle");
            if (pageTitle != null)
            {
                cfg.setPageTitle(pageTitle);
            }
            
            String useDirectBlit = u.swfMetaData.getValue("useDirectBlit");
            if(useDirectBlit != null)
            {
            	cfg.setUseDirectBlit(Boolean.parseBoolean(useDirectBlit));
            }
            
            String useGPU = u.swfMetaData.getValue("useGPU");
            if(useGPU != null)
            {
            	cfg.setUseGpu(Boolean.parseBoolean(useGPU));
            }

            // fixme: error on SWF metadata we don't understand
        }
    }

    private SortedSet<String> resourceBundleNames = new TreeSet<String>();
    private SortedSet<String> externalResourceBundleNames = new TreeSet<String>();

    private void processResourceBundleNames(List units, flex2.compiler.common.Configuration configuration)
    {
        Set externs = configuration.getExterns();

         for (Iterator it = units.iterator(); it.hasNext();)
        {
            CompilationUnit unit = (CompilationUnit) it.next();
            if (unit.resourceBundleHistory.size() > 0)
            {
                resourceBundleNames.addAll(unit.resourceBundleHistory);

                if (externs.contains(unit.topLevelDefinitions.first().toString()))
                {
                    externalResourceBundleNames.addAll(unit.resourceBundleHistory);
                }
            }
        }
    }

    private void processInitClass(List units, Configuration configuration,
                                  List<Source> extraSources, LinkedList<String> mixins,
                                  LinkedList<DefineTag> fonts,
                                  Set<String> contributingSwcs,
                                  CompilerSwcContext swcContext)
    {
        Set<String> accessibilityList = null;
        Map<String, String> remoteClassAliases = new TreeMap<String, String>()
        {
            private static final long serialVersionUID = -8015004853369794727L;

            /**
             *  Override so warning messages can be logged. 
             */
            public String put(String key, String value)
            {
                // check for duplicate values and log a warning if any remote 
                // classes try to use the same alias.
                if (containsValue(value))
                {
                    Object existingKey = null;
                    for (Iterator iter = entrySet().iterator(); iter.hasNext();)
                    {
                        Map.Entry entry = (Map.Entry)iter.next();
                        if (value != null && value.equals(entry.getValue()))
                        {
                            existingKey = entry.getKey();
                            break;
                        }
                    }
                    ThreadLocalToolkit.log(new ClassesMappedToSameRemoteAlias((String)key, (String)existingKey, (String)value));
                }
                return super.put(key, value);
            }

        };

        Set externs = swcContext.getExterns();
        boolean removeUnusedRSLs = configuration.getRemoveUnusedRsls();

        for (int i = 0, size = units.size(); i < size; i++)
        {
            CompilationUnit u = (CompilationUnit) units.get(i);

            if (u.hasAssets())
            {
                List<DefineFont> fontList = u.getAssets().getFonts();

                // don't add font assets for definitions that have been externed.
                if (fontList != null && !fontList.isEmpty() &&
                        !isCompilationUnitExternal(u, externs) &&
                        !u.getSource().isInternal())
                {
                    fonts.addAll(fontList);    // save for later...
                }
            }

            remoteClassAliases.putAll( u.remoteClassAliases );

            mixins.addAll( u.mixins );

            if (configuration.getCompilerConfiguration().accessible())
            {
                Set<String> unitAccessibilityList = u.getAccessibilityClasses();
                if (unitAccessibilityList != null)
                {
                    if (accessibilityList == null)
                    {
                        accessibilityList = new HashSet<String>();
                    }
                    accessibilityList.addAll(unitAccessibilityList);
                }
            }

            if (removeUnusedRSLs)
            {
                // Record which swcs have contributed the scripts. We will use
                // this later to figure out which RSLs to load.
                Source source = u.getSource();

                if (!source.isInternal() && source.isSwcScriptOwner())
                {
                    SwcScript script = (SwcScript)source.getOwner();
                    contributingSwcs.add(script.getSwcLocation());
                }
            }
        }
    }

    /**
     *
     * @param unit compilation unit, may not be null
     * @param externs - list of externs, may not be null
     * @return true if the compilation unit, u, has any definitions that are in the list of
     *            interns.
     */
    public static boolean isCompilationUnitExternal(CompilationUnit unit, Set externs)
    {
        for (int i = 0, size = unit == null ? 0 : unit.topLevelDefinitions.size(); i < size; i++)
        {
            if (externs.contains(unit.topLevelDefinitions.get(i).toString()))
            {
                return true;
            }
        }

        return false;
    }


    private boolean processLoaderClass(List units,
                                       Configuration configuration,
                                       List<Source> sources,
                                       List<String> mixins,
                                       List<DefineTag> fonts,
                                       Set<String> contributingSwcs,
                                       CompilerSwcContext swcContext)
    {
        if (!configuration.generateFrameLoader)
        {
            return false;
        }

        LinkedList<FrameInfo> frames = new LinkedList<FrameInfo>();
        frames.addAll( configuration.getFrameList() );

        CompilationUnit mainUnit = null;
         for (Iterator it = units.iterator(); it.hasNext();)
        {
            CompilationUnit unit = (CompilationUnit) it.next();
            if (unit.isRoot())
            {
                mainUnit = unit;
                break;
            }
        }

        if (mainUnit == null)
        {
            return false;
        }

        // If we built the main unit from source on this pass, we will have saved
        // off information that will help us determine whether we need to generate
        // an IFlexModuleFactory derivative.
        //
        // IMPORTANT: Having frame metadata is NOT the indicator!  We only generate
        // a system manager in sync with compiling a MXML application from source;
        // otherwise, the generated class is assumed to already exist!

        String generateLoaderClass = null;
        String baseLoaderClass = null;
        String windowClass = null;
        //String preloaderClass = null;
        Map<String, Object> rootAttributes = null;
        Map<String, Object> rootAttributeEmbedVars = null;
        Map<String, Object> rootAttributeEmbedNames = null;
        //boolean usePreloader = false;
        List<RslPathInfo> cdRsls = configuration.getRslPathInfo();
        List<String> rsls = configuration.getRuntimeSharedLibraries();
        String[] locales = configuration.getCompilerConfiguration().getLocales();

        // ALGORITHM:
        // Generate a loader class iff all the below are true:
        // 1a. We compiled MXML on this compilation run.
        //   or
        // 1b. We were not MXML but the base class does know a loader.
        // 2. We found Frame loaderClass metadata in a superclass
        // 3. We did not find Frame loaderClass metadata in the app



        if ((mainUnit.loaderClass != null) && (mainUnit.auxGenerateInfo != null))
        {
            generateLoaderClass = (String) mainUnit.auxGenerateInfo.get("generateLoaderClass");
            baseLoaderClass = (String) mainUnit.auxGenerateInfo.get("baseLoaderClass");
            windowClass = (String) mainUnit.auxGenerateInfo.get("windowClass");
            //preloaderClass = (String) mainUnit.auxGenerateInfo.get("preloaderClass");
            //Boolean b = (Boolean) mainUnit.auxGenerateInfo.get("usePreloader");

            @SuppressWarnings("unchecked")
            Map<String, Object> tmpRootAttributes = (Map<String, Object>) mainUnit.auxGenerateInfo.get("rootAttributes");
            rootAttributes = tmpRootAttributes;
            @SuppressWarnings("unchecked")
            Map<String, Object> tmpRootAttributeEmbedVars = (Map<String, Object>) mainUnit.auxGenerateInfo.get("rootAttributeEmbedVars");
            rootAttributeEmbedVars = tmpRootAttributeEmbedVars;
            @SuppressWarnings("unchecked")
            Map<String, Object> tmpRootAttributeEmbedNames = (Map<String, Object>) mainUnit.auxGenerateInfo.get("rootAttributeEmbedNames");
            rootAttributeEmbedNames = tmpRootAttributeEmbedNames;

            // mainUnit.auxGenerateInfo = null;    // All done, thanks!

            assert generateLoaderClass != null;

            //usePreloader = ((b == null) || b.booleanValue());

            //assert usePreloader || (preloaderClass != null);

            // Is there any way we can eliminate having default class here?
            // Seems like this should be in SystemManager, not the compiler.

            //if (usePreloader && (preloaderClass == null))   //
            //{
            //    preloaderClass = "mx.preloaders.DownloadProgressBar";
            //}
        }
        else if ((mainUnit.loaderClass == null) && (mainUnit.loaderClassBase != null))
        {
            // AS project, but the base class knows of a loader.
            baseLoaderClass = mainUnit.loaderClassBase;
            windowClass = mainUnit.topLevelDefinitions.last().toString();
            generateLoaderClass = (windowClass + "_" + mainUnit.loaderClassBase).replaceAll("[^A-Za-z0-9]", "_");

            mainUnit.loaderClass = generateLoaderClass;
        }
        else if ((mainUnit.loaderClass == null) &&
                ((rsls.size() > 0) || (cdRsls.size() > 0)))
        {
            ThreadLocalToolkit.log(new MissingFactoryClassInFrameMetadata(), mainUnit.getSource());
            return false;
        }
        else
        {
            return false;
        }

        String generatedLoaderCode = codegenModuleFactory(baseLoaderClass.replace(':', '.'),
                                                              generateLoaderClass.replace(':', '.'),
                                                              windowClass.replace(':', '.'),
                                                              rootAttributes,
                                                              rootAttributeEmbedVars,
                                                              rootAttributeEmbedNames,
                                                              cdRsls,
                                                              rsls,
                                                              mixins,
                                                              fonts,
                                                              frames,
                                                              locales,
                                                              resourceBundleNames,
                                                              externalResourceBundleNames,
                                                              configuration,
                                                              contributingSwcs,
                                                              swcContext,
                                                              false);

        String generatedLoaderFile = generateLoaderClass + ".as";

        TextFile genSource = new TextFile(generatedLoaderCode,
                generatedLoaderFile,
                mainUnit.getSource().getParent(),
                MimeMappings.AS);

        Source s = new Source(genSource, mainUnit.getSource(), generateLoaderClass, false, false);
        sources.add(s);

        if (configuration.getCompilerConfiguration().keepGeneratedActionScript())
        {
            saveGenerated(generatedLoaderFile, generatedLoaderCode, configuration.getCompilerConfiguration().getGeneratedDirectory());
        }

        return true;
    }


    private String codegenAccessibilityImports(Set<String> accessibilityImplementations)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("import flash.system.*\n");
        if (accessibilityImplementations != null)
        {
            for (Iterator<String> it = accessibilityImplementations.iterator(); it.hasNext();)
            {
                sb.append("import " + it.next() + ";\n");
            }
        }

        return sb.toString();
    }

    //    TODO save to alt location instead of mangling name, to keep code compilable under OPD
    //    TODO make sure code generators obey OPD in name <-> code
    public static void saveGenerated(String name, String code, String dir)
    {
        final String suffix = "-generated.as";
        final String as3ext = ".as";
        if (!name.endsWith(suffix) && name.endsWith(as3ext))
        {
            name = name.substring(0, name.length() - as3ext.length()) + suffix;
        }

        name = FileUtils.addPathComponents( dir, name, File.separatorChar );

        try
        {
            FileUtil.writeFile(name, code);
        }
        catch (IOException e)
        {
            ThreadLocalToolkit.log(new VelocityException.UnableToWriteGeneratedFile(name, e.getLocalizedMessage()));
        }
    }

    private static String codegenMixinList(List<String> mixins)
    {
        assert mixins != null && mixins.size() > 0;
        StringJoiner.ItemStringer itemStringer = new StringJoiner.ItemQuoter();
        return "[ " + StringJoiner.join(mixins, ", ", itemStringer) + " ]";
    }

    private static String codegenFrameClassList(List<FrameInfo> frames)
    {
        assert frames != null && frames.size() > 0;
        StringBuilder mb = new StringBuilder();
        mb.append("{");

        for (Iterator<FrameInfo> it = frames.iterator(); it.hasNext();)
        {
            FramesConfiguration.FrameInfo frameInfo = it.next();
            mb.append("\"");
            mb.append(frameInfo.label);
            mb.append("\":\"");
            mb.append(frameInfo.frameClasses.get(0));
            mb.append("\"");
            if (it.hasNext())
            {
                mb.append(", ");
            }
        }
        mb.append("}\n");
        return mb.toString();
    }

    private static String codegenFontList(List<DefineTag> fonts)
    {
        if ((fonts == null) || (fonts.size() == 0))
        {
            return "";
        }

        class FontInfo
        {
            boolean plain;
            boolean bold;
            boolean italic;
            boolean bolditalic;
        }

        Map<String, FontInfo> fontMap = new TreeMap<String, FontInfo>();
        for (Iterator<DefineTag> it = fonts.iterator(); it.hasNext();)
        {
            DefineFont font = (DefineFont) it.next();
            FontInfo fi = fontMap.get( font.getFontName() );
            if (fi == null)
            {
                fi = new FontInfo();
                fontMap.put( font.getFontName(), fi );
            }

            fi.plain |= (!font.isBold() && !font.isItalic());
            fi.bolditalic |= (font.isBold() && font.isItalic());
            fi.bold |= font.isBold();
            fi.italic |= font.isItalic();
        }

        StringBuilder sb = new StringBuilder();

        sb.append("      {\n");

        for (Iterator it = fontMap.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry e = (Map.Entry) it.next();
            String fontName = (String) e.getKey();
            FontInfo fontInfo = (FontInfo) e.getValue();

            sb.append("\"" + fontName + "\" : {" +
                      "regular:" + (fontInfo.plain? "true":"false") +
                      ", bold:" + (fontInfo.bold? "true":"false") +
                      ", italic:" + (fontInfo.italic? "true":"false") +
                      ", boldItalic:" + (fontInfo.bolditalic? "true":"false") + "}\n");
            if (it.hasNext())
            {
                sb.append(",\n");
            }
        }
        sb.append("}\n");

        return sb.toString();
    }

    private String codegenAccessibilityList(Set<String> accessibilityImplementations)
    {
        if ((accessibilityImplementations == null) || (accessibilityImplementations.size() == 0))
        {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        if ((accessibilityImplementations != null) && (accessibilityImplementations.size() != 0))
        {
            sb.append("       // trace(\"Flex accessibility startup: \" + Capabilities.hasAccessibility);\n");
            sb.append("       if (Capabilities.hasAccessibility) {\n");
            for (Iterator<String> it = accessibilityImplementations.iterator(); it.hasNext();)
            {
                sb.append("          " + it.next() + ".enableAccessibility();\n");
            }
            sb.append("       }\n");
        }

        if (Trace.accessible)
        {
            Trace.trace("codegenAccessibilityList");
            if (sb.length() > 0)
            {
                Trace.trace(sb.toString());
            }
            else
            {
                Trace.trace("empty");
            }
        }

        return sb.toString();
    }

    /**
     * Returns a string like
     *   [ "en_US", "ja_JP" ]
     */
    private static String codegenCompiledLocales( String[] locales )
    {
        StringJoiner.ItemStringer itemStringer = new StringJoiner.ItemQuoter();
        return "[ " + StringJoiner.join(locales, ", ", itemStringer) + " ]";
    }

    /**
     * Returns a string like
     *   [ "core", "controls", "MyApp" ]
     */
    private static String codegenCompiledResourceBundleNames( SortedSet<String> bundleNames )
    {
        StringJoiner.ItemStringer itemStringer = new StringJoiner.ItemQuoter();
        return "[ " + StringJoiner.join(bundleNames, ", ", itemStringer) + " ]";
    }

    static String codegenModuleFactory(String base,
                                        String rootClassName,
                                        String topLevelWindowClass,
                                        Map<String, Object> rootAttributes,
                                        Map<String, Object> rootAttributeEmbedVars,
                                        Map<String, Object> rootAttributeEmbedNames,
                                        List<RslPathInfo> cdRsls,
                                        List<String> rsls,
                                        List<String> mixins,
                                        List<DefineTag> fonts,
                                        List<FrameInfo> frames,
                                        String[] locales,
                                        SortedSet<String> resourceBundleNames,
                                        SortedSet<String> externalResourceBundleNames,
                                        Configuration configuration,
                                        Set<String> contributingSwcs,
                                        CompilerSwcContext swcContext,
                                        boolean isLibraryCompile)
    {
        String lineSep = System.getProperty("line.separator");
        boolean hasFonts = (fonts == null ? false : fonts.size() > 0);
        StandardDefs standardDefs = ThreadLocalToolkit.getStandardDefs();

        String[] codePieces = new String[]
        {
            "package", lineSep,
            "{", lineSep, lineSep,
            codegenImports(base, rootAttributes, rootAttributeEmbedVars, fonts, configuration, standardDefs, isLibraryCompile, hasFonts),
            codegenResourceBundleMetadata(null /*externalResourceBundleNames*/),
            "[ExcludeClass]", lineSep,
            "public class ", rootClassName, lineSep,
            "    extends ", base, lineSep,
            "{", lineSep,
            "    public function ", rootClassName, "()", lineSep,
            "    {", lineSep,
            "        super();", lineSep,
            "    }", lineSep, lineSep,
            codegenEmbeddedApplicaitonAttributes(rootAttributeEmbedVars),
            "    /**", lineSep,
            "     *  @private", lineSep,
            "     */", lineSep,
            "    private var _info:Object;", lineSep, lineSep,
            !isLibraryCompile ?
            "    override" : "",
            "    public function info():Object", lineSep,
            "    {", lineSep,
            "        if (!_info)", lineSep,
            "        {", lineSep,
            "            _info = {", lineSep,
            codegenInfo(topLevelWindowClass, rootAttributes, rootAttributeEmbedNames, cdRsls, rsls, mixins, fonts,
                        frames, locales, resourceBundleNames, configuration, 
                        contributingSwcs, swcContext),
            "            }", lineSep,
            "        }", lineSep,
            "        return _info;", lineSep,
            "    }", lineSep, lineSep,
            "}", lineSep, lineSep,
            "}", lineSep,
        };

        return StringJoiner.join(codePieces, null);
    }

    private static String codegenResourceBundleMetadata(SortedSet<String> resourceBundleNames)
    {
        if (resourceBundleNames == null)
        {
            return "";
        }
        String lineSep = System.getProperty("line.separator");

        StringBuilder codePieces = new StringBuilder();
        for (Iterator<String> i = resourceBundleNames.iterator(); i.hasNext(); )
        {
            codePieces.append("[ResourceBundle(\"" + i.next() + "\")]" + lineSep);
        }

        return codePieces.toString();
    }


    private static String codegenImports(String base, Map<String, Object> rootAttributes, 
    									Map<String, Object> rootAttributeEmbedVars,	List<DefineTag> fonts,
                                        Configuration configuration, StandardDefs standardDefs, 
                                        boolean isLibraryCompile, boolean hasFonts)
    {
        String lineSep = System.getProperty("line.separator");

        StringBuilder sb = new StringBuilder(512);
        sb.append("import flash.display.LoaderInfo;").append(lineSep);
        sb.append("import flash.system.ApplicationDomain;").append(lineSep);
        sb.append("import flash.system.Security").append(lineSep);
        sb.append("import flash.utils.Dictionary;").append(lineSep);
        sb.append("import flash.utils.getDefinitionByName;").append(lineSep);

        sb.append("import ").append(base).append(";").append(lineSep);
        
        // TODO - eliminate any special handling of preloaderDisplayClass!
        String preloader = getPreloaderClass(rootAttributes, configuration);
        if (preloader != null)
        {
            sb.append("import ").append(preloader).append(";").append(lineSep);
        }

        // If the splashScreenImage was specified as a Class and not @Embed, we need to import it here
        if (rootAttributes != null && rootAttributes.containsKey("splashScreenImage") && 
            (rootAttributeEmbedVars == null || !rootAttributeEmbedVars.containsKey("splashScreenImage")))
        {
            String splashImageClass = (String) rootAttributes.get("splashScreenImage");
            sb.append("import ").append(splashImageClass).append(";").append(lineSep);
        }

        sb.append(lineSep);

        return sb.toString();
    }
    
    private static String getThemeClass(Map<String, Object> rootAttributes, Configuration configuration)
    {
        // Is theme attribute defined on the root tag?
        if (rootAttributes != null && rootAttributes.containsKey("theme"))
            return (String)rootAttributes.get("theme");
        
        return null;
    }
    
    private static String getPreloaderClass(Map<String, Object> rootAttributes, Configuration configuration)
    {
        // Is preloader attribute defined on the root tag?
        if (rootAttributes != null && rootAttributes.containsKey("preloader"))
            return (String)rootAttributes.get("preloader");

        // Is preloader specified in the compiler arguments?
        String preloader = configuration.getCompilerConfiguration().getPreloader();
        if (preloader != null)
        {
        	preloader = preloader.trim();
        	return preloader.length() == 0 ? null : preloader;
        }

        return null;
    }

    private static String codegenEmbeddedApplicaitonAttributes(Map<String, Object> rootAttributeEmbedVars)
    {
        if (rootAttributeEmbedVars == null || rootAttributeEmbedVars.size() == 0)
            return "";

        String lineSep = System.getProperty("line.separator");
        return StringJoiner.join(rootAttributeEmbedVars.values(), lineSep) + lineSep;
    }

    private static String codegenInfo(String topLevelWindowClass,
                       Map<String, Object> rootAttributes,
                       Map<String, Object> rootAttributeEmbedNames,
                       List<RslPathInfo> cdRsls,
                       List<String> rsls,
                       List<String> mixins,
                       List<DefineTag> fonts,
                       List<FrameInfo> frames,
                       String[] locales,
                       SortedSet<String> resourceBundleNames,
                       Configuration configuration,
                       Set<String> contributingSwcs,
                       CompilerSwcContext swcContext)
    {
        // Build a map of the name/value pairs for the info
        TreeMap<String, Object> t = new TreeMap<String, Object>();

        t.put("currentDomain", "ApplicationDomain.currentDomain");

        if (topLevelWindowClass != null)
        {
            //this is Starling's root class, not the generated root class.
            t.put("rootClassName", "\"" + topLevelWindowClass + "\"");
        }

        if (rootAttributes != null)
        {
            for (Iterator<Map.Entry<String, Object>> it = rootAttributes.entrySet().iterator(); it.hasNext(); )
            {
                Map.Entry<String, Object> e = it.next();

                // TODO - eliminate any special handling of preloaderDisplayClass!
                if ("preloader".equals(e.getKey()))
                {
                    // skip, will handle preloader after the loop
                }
                else if ("usePreloader".equals(e.getKey()))
                {
                    t.put(e.getKey(), e.getValue());
                }
                else if ("theme".equals(e.getKey()))
                {
                    // skip, will handle theme after the loop
                }
                else if ("implements".equals(e.getKey()))
                {
                    // skip
                }
                else if ("backgroundColor".equals(e.getKey()))
                {
                    t.put(e.getKey(), "\"0x" + Integer.toHexString(configuration.backgroundColor()).toUpperCase() + "\"");
                }
                else
                {
                    String embedName = null;
                    if (rootAttributeEmbedNames != null)
                        embedName = (String) rootAttributeEmbedNames.get(e.getKey());

                    if (embedName != null)
                        t.put(e.getKey(), embedName);
                    else if ("splashScreenImage".equals(e.getKey()))
                        t.put(e.getKey(), e.getValue()); // SplashScreenImage is a special attribute of type Class.
                    else
                        t.put(e.getKey(), "\"" + e.getValue() + "\"");
                }
            }
            
            String preloader = getPreloaderClass(rootAttributes, configuration);
            if (preloader != null)
            {
                // we use the actual class in order to get a link dependency
                t.put("preloader", preloader);
            }

            String themeClassName = getThemeClass(rootAttributes, configuration);
            if (themeClassName != null)
            {
            	t.put("themeClassName", "\"" + themeClassName + "\"");
            }
        }

        if ((mixins != null) && (mixins.size() > 0))
            t.put("mixins", codegenMixinList(mixins));

        if ((fonts != null) && (fonts.size() > 0))
            t.put("fonts", codegenFontList(fonts) );

        if ((frames != null) && (frames.size() > 0))
            t.put("frames", codegenFrameClassList(frames));

        if (locales != null)
            t.put("compiledLocales", codegenCompiledLocales(locales));

        if ((resourceBundleNames != null) && (resourceBundleNames.size() > 0))
            t.put("compiledResourceBundleNames", codegenCompiledResourceBundleNames(resourceBundleNames));

        // Codegen a string from that map.
        String lineSep = System.getProperty("line.separator");
        StringJoiner.ItemStringer itemStringer = new StringJoiner.MapEntryItemWithColon();
        return "            " +
               StringJoiner.join(t.entrySet(), "," + lineSep + "            ", itemStringer) +
               lineSep;
    }

    private void processCompiledResourceBundleInfoClass(List units,
            Configuration configuration,
            List<Source> sources,
            List<String> mixins,
            List<DefineTag> fonts,
            CompilerSwcContext swcContext)
    {
        CompilerConfiguration config = configuration.getCompilerConfiguration();

        // Don't add the _CompiledResourceBundleInfo class
        // if we are compiling in Flex 2 compatibility mode,
        // or if there are no locales,
        // or if there are no resource bundle names.

        int version = config.getCompatibilityVersion();
        if (version < MxmlConfiguration.VERSION_3_0)
        {
            return;
        }

        String[] locales = config.getLocales();
        if (locales.length == 0)
        {
            return;
        }

        if (resourceBundleNames.size() == 0)
        {
            return;
        }

        String className = I18nUtils.COMPILED_RESOURCE_BUNDLE_INFO;
        String code = I18nUtils.codegenCompiledResourceBundleInfo(locales, resourceBundleNames);

        String generatedFileName = className + "-generated.as";
        if (config.keepGeneratedActionScript())
        {
            saveGenerated(generatedFileName, code, config.getGeneratedDirectory());
        }

        Source s = new Source(new TextFile(code, generatedFileName, null,
                              MimeMappings.getMimeType(generatedFileName)),
                              "", className, null, false, false, false);
        s.setPathResolver(null);
        sources.add(s);

        // Ensure that this class gets linked in,
        // because no other code depends on it.
        // (ResourceManager looks it up by name.)
        configuration.getIncludes().add(className);
    }

    // error messages

    public static class DependencyNotCompatible extends CompilerMessage.CompilerError
    {
        private static final long serialVersionUID = -917715346261180364L;

        public String definition;
        public String swc;
        public String swcMinimumVersion;
        public String compatibilityVersion;

        public DependencyNotCompatible(String definition, String swc,
                                       String swcMinimumVersion, String compatibilityVersion)
        {
            this.definition = definition;
            this.swc = swc;
            this.swcMinimumVersion = swcMinimumVersion;
            this.compatibilityVersion = compatibilityVersion;
        }
    }

    public static class NoExternalVisibleDefinition extends CompilerMessage.CompilerError
    {
        private static final long serialVersionUID = -917715346261180363L;

        public NoExternalVisibleDefinition()
        {
            super();
        }
    }

    public static class MissingFactoryClassInFrameMetadata extends CompilerMessage.CompilerWarning
    {
        private static final long serialVersionUID = 1064989348731483344L;

        public MissingFactoryClassInFrameMetadata()
        {
            super();
        }
    }

    public static class InvalidBackgroundColor extends CompilerMessage.CompilerError
    {
        private static final long serialVersionUID = -623864938378435687L;

        public String backgroundColor;

        public InvalidBackgroundColor(String backgroundColor)
        {
            super();
            this.backgroundColor = backgroundColor;
        }
    }

    public static class CouldNotParseNumber extends CompilerMessage.CompilerError
    {
        private static final long serialVersionUID = 2186380089141871093L;

        public CouldNotParseNumber(String num, String attribute)
        {
            this.num = num;
            this.attribute = attribute;
        }

        public String num;
        public String attribute;
    }

    public static class MissingSignedLibraryDigest extends CompilerMessage.CompilerError
    {
        private static final long serialVersionUID = -1865860949469218550L;

        public MissingSignedLibraryDigest(String libraryPath)
        {
            this.libraryPath = libraryPath;
        }

        public String libraryPath;
    }

    public static class MissingUnsignedLibraryDigest extends CompilerMessage.CompilerError
    {
        private static final long serialVersionUID = 8092666584208136222L;

        public MissingUnsignedLibraryDigest(String libraryPath)
        {
            this.libraryPath = libraryPath;
        }

        public String libraryPath;
    }

	/**
	 *  Warn users with [RemoteClass] metadata that ends up mapping more than one class to the same alias. 
	 */
    public static class ClassesMappedToSameRemoteAlias extends CompilerMessage.CompilerWarning
    {
        private static final long serialVersionUID = 4365280637418299961L;
        
        public ClassesMappedToSameRemoteAlias(String className, String existingClassName, String alias)
        {
            this.className = className;
            this.existingClassName = existingClassName;
            this.alias = alias;
        }

        public String className;
        public String existingClassName;
        public String alias;
    }

    /**
     *  Tell the user they are making a mistake by compiling a module or application as a component. 
     */
    public static class CompiledAsAComponent extends CompilerMessage.CompilerWarning
    {
        private static final long serialVersionUID = -2874508107726441350L;

        public CompiledAsAComponent(String className, String mainDefinition)
        {
            this.className = className;
            this.mainDefinition = mainDefinition;
        }
        
        public String className;
        public String mainDefinition;

    }    
  
    /**
     *  "Required RSLs:" message. 
     */
    public static class RequiredRsls extends CompilerMessage.CompilerInfo
    {
        private static final long serialVersionUID = 2303666861783668660L;

        public RequiredRsls()
        {
        }

    }
    
    /**
     *  Display RSL URL with no failovers. 
     */
    public static class RequiredRslUrl extends CompilerMessage.CompilerInfo
    {
        private static final long serialVersionUID = 2303666861783668660L;

        public RequiredRslUrl(String rslUrl)
        {
            this.rslUrl = rslUrl;
        }

        public String rslUrl;
    }

    /**
     *  Display RSL URL with one failover. 
     */
    public static class RequiredRslUrlWithFailover extends CompilerMessage.CompilerInfo
    {
        private static final long serialVersionUID = 2303666861783668660L;

        public RequiredRslUrlWithFailover(String rslUrl)
        {
            this.rslUrl = rslUrl;
        }

        public String rslUrl;
    }

    /**
     *  Display RSL URL with more than one failovers. 
     */
    public static class RequiredRslUrlWithMultipleFailovers extends CompilerMessage.CompilerInfo
    {
        private static final long serialVersionUID = 2303666861783668660L;

        public RequiredRslUrlWithMultipleFailovers(String rslUrl, int failoverCount)
        {
            this.rslUrl = rslUrl;
            this.failoverCount = failoverCount;
        }
        
        public String rslUrl;
        public int failoverCount;

    }
    
}
