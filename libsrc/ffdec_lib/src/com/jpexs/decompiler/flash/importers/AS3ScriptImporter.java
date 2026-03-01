/*
 *  Copyright (C) 2010-2025 JPEXS, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.jpexs.decompiler.flash.importers;

import com.jpexs.decompiler.flash.IdentifiersDeobfuscation;
import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.abc.ABC;
import com.jpexs.decompiler.flash.abc.ScriptPack;
import com.jpexs.decompiler.flash.abc.avm2.parser.AVM2ParseException;
import com.jpexs.decompiler.flash.abc.avm2.parser.script.AbcIndexing;
import com.jpexs.decompiler.flash.abc.avm2.parser.script.ActionScript3Parser;
import com.jpexs.decompiler.flash.abc.avm2.parser.script.NamespaceItem;
import com.jpexs.decompiler.flash.exporters.modes.ScriptExportMode;
import com.jpexs.decompiler.flash.exporters.settings.ScriptExportSettings;
import com.jpexs.decompiler.flash.tags.ABCContainerTag;
import com.jpexs.decompiler.flash.tags.Tag;
import com.jpexs.decompiler.flash.treeitems.Openable;
import com.jpexs.decompiler.graph.CompilationException;
import com.jpexs.decompiler.graph.DottedChain;
import com.jpexs.helpers.CancellableWorker;
import com.jpexs.helpers.Helper;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ActionScript 3 scripts importer.
 *
 * @author JPEXS
 */
public class AS3ScriptImporter {

    private static final Logger logger = Logger.getLogger(AS3ScriptImporter.class.getName());

    /**
     * Constructor.
     */
    public AS3ScriptImporter() {

    }

    /**
     * Imports scripts from a folder.
     * @param scriptReplacer Replacer for the scripts
     * @param scriptsFolder Folder with scripts
     * @param packs List of script packs
     * @param dependencies List of dependencies
     * @param NewScriptABCContainer ABCContainerTag for new scripts to be put into. Ignores new scripts if null.
     * @return Number of imported scripts
     * @throws InterruptedException On interrupt
     */
    public int importScripts(As3ScriptReplacerInterface scriptReplacer, String scriptsFolder, List<ScriptPack> packs, List<SWF> dependencies, ABCContainerTag NewScriptABCContainer) throws InterruptedException {
        return importScripts(scriptReplacer, scriptsFolder, packs, null, dependencies, NewScriptABCContainer);
    }
    
    /**
     * Imports scripts from a folder.
     * @param scriptReplacer Replacer for the scripts
     * @param scriptsFolder Folder with scripts
     * @param packs List of script packs
     * @param listener Listener for progress
     * @param dependencies List of dependencies
     * @param NewScriptABCContainer ABCContainerTag for new scripts to be put into. Ignores new scripts if null.
     * @return Number of imported scripts
     * @throws InterruptedException On interrupt
     */
    public int importScripts(As3ScriptReplacerInterface scriptReplacer, String scriptsFolder, List<ScriptPack> packs, ScriptImporterProgressListener listener, List<SWF> dependencies, ABCContainerTag NewScriptABCContainer) throws InterruptedException {
        if (!scriptsFolder.endsWith(File.separator)) {
            scriptsFolder += File.separator;
        }
        int importCount = 0;

        Openable openable = packs.get(0).getOpenable(); // wait wouldn't this break if there are no scripts?
        SWF swf = (openable instanceof SWF) ? (SWF) openable : ((ABC) openable).getSwf();
        
        if(NewScriptABCContainer != null){
            ArrayList<File> allFiles = recursivelySearchDirForScripts(scriptsFolder);
            ArrayList<String> newScriptContents = new ArrayList<String>();
            ArrayList<ActionScript3Parser.importsAndCustomNamespaces> newScriptDependencies = new ArrayList<ActionScript3Parser.importsAndCustomNamespaces>();
            ArrayList<String> newFileDotPaths = new ArrayList<>();

            for(int i = 0; i < allFiles.size(); i++)
            {
                File curFile = allFiles.get(i);
                // TODO: check how symlinks are handled.
                if(!curFile.getAbsolutePath().contains(scriptsFolder))
                {
                    logger.log(Level.WARNING, "Found %file% while recursively searching for new scripts, ".replace("%file%", curFile.getAbsolutePath())
                            + "which doesn't exist inside %folder%, the folder being imported.".replace("%folder%", scriptsFolder));
                    continue;
                }
                String fileRelativePath = curFile.getAbsolutePath().substring(scriptsFolder.length());
                fileRelativePath = fileRelativePath.substring(0, fileRelativePath.lastIndexOf("."));
                fileRelativePath = fileRelativePath.replace("/", ".").replace("\\", ".");
                System.out.println(fileRelativePath);
                // does this script *not* already exist in the swf?
                if(packs.get(0).abc.findScriptPacksByPath(fileRelativePath, packs.get(0).allABCs).isEmpty()) // does this use the correct ABC tag
                {
                    System.out.println("^ new script to import!");
                    // ok so in this step we also want to get the imports/namespaces. then after this loop we do a second loop to topologically sort them, and then
                    // we do a third loop where we finally compile them in order.
                    try{
                        newFileDotPaths.add(fileRelativePath);
                        newScriptContents.add(Helper.readTextFile(curFile.getAbsolutePath()));
                        int indexForNewScript = newScriptContents.size() - 1;
                        ActionScript3Parser parser = new ActionScript3Parser(swf.getAbcIndex()); // TODO: this should probably support more than just actionscript 3.
                        ActionScript3Parser.importsAndCustomNamespaces importedClassesAndCustomNamespaces = parser.parseAndReturnScriptImports(newScriptContents.get(indexForNewScript), fileRelativePath, NewScriptABCContainer.getABC());
                        newScriptDependencies.add(importedClassesAndCustomNamespaces);
                        List<DottedChain> scriptImportList = importedClassesAndCustomNamespaces.importedClasses;
                        List<DottedChain> usedCustomNamespaces = importedClassesAndCustomNamespaces.usedCustomNamespaces;
                        List<String> definedCustomNamespaces = importedClassesAndCustomNamespaces.definedCustomNamespaces;
                        // debugging start
                        String importsOutputString = "";
                        String usedNamespacesOutputString = "";
                        String definedNamespacesOutputString = "";
                        for(int j = 0; j < scriptImportList.size(); j++)
                        {
                            importsOutputString += "\n - [" + scriptImportList.get(j).toPrintableString(new LinkedHashSet<>(), swf, true) + "]";
                        }
                        for(int j = 0; j < usedCustomNamespaces.size(); j++)
                        {
                            usedNamespacesOutputString +=  "\n - [" + usedCustomNamespaces.get(j).toPrintableString(new LinkedHashSet<>(), swf, true)  + "]";
                        }
                        for(int j = 0; j < definedCustomNamespaces.size(); j++)
                        {
                            definedNamespacesOutputString +=  "\n - [" + definedCustomNamespaces.get(j)  + "]";
                        }
                        System.out.println(fileRelativePath + " imports: " + importsOutputString + "\n------\n " + fileRelativePath + " used namespaces: " + usedNamespacesOutputString + "\n------\n " + fileRelativePath + " defined namespaces: " + definedNamespacesOutputString);
                        // debugging end
                    }
                    catch(Exception e)
                    {
                        logger.log(Level.SEVERE, "Error while trying to parse imports of new scripts: " + e.getMessage());
                    }
                    // this is a script that doesn't already exist in the swf. We need to handle it differently.                    
                    //addNewClassBeingImported(fileRelativePath, curFile, NewScriptABCContainer, swf);
                    //newFileDotPaths.add(fileRelativePath);
                }
            }
            
            // compile newly imported dependencies in order! we need to do this for compile time constants that exist in other classes.
            // At time of writing static const variables aren't treated as compile time constants anyway, but hopefully that'll be fixed/added in future.
            // This is also needed for custom namespaces, as the compiler throws an error if a script tries to use a custom NS that doesn't exist.
            // see https://en.wikipedia.org/wiki/Topological_sorting#Depth-first_search
            ArrayList<Integer> orderedScriptIndices = new ArrayList<>(); 
            ArrayList<Integer> tempMarkedScriptIndices = new ArrayList<>();
            ArrayList<Integer> nextScriptIndicesToVisit = new ArrayList<>();
            // TODO: make this cancelable with the cancellable worker thing like the main loop is
            for(int i = 0; i < newScriptContents.size(); i++)
            {
                if(tempMarkedScriptIndices.contains(i) || orderedScriptIndices.contains(i))
                {
                    continue;
                }
                nextScriptIndicesToVisit.add(i);
                for(int j = 0; j < nextScriptIndicesToVisit.size(); j++)
                {
                    int scriptIndex = nextScriptIndicesToVisit.remove(0);
                    if(orderedScriptIndices.contains(scriptIndex))
                    {
                        continue;
                    }
                    if(tempMarkedScriptIndices.contains(scriptIndex))
                    {
                        logger.log(Level.SEVERE, "CYCLIC IMPORT DETECTED WHILE IMPORTING NEW SCRIPTS!"); // TODO: throw an error here
                    }
                    tempMarkedScriptIndices.add(scriptIndex); // should i actually be adding to the front?
                    
                    // use this script's imports to check other scripts
                    for(int k = 0; k < newScriptDependencies.get(scriptIndex).importedClasses.size(); k++)
                    {
                        // oh wait yeah no this isn't gonna work without an actual recursive function call here
                        nextScriptIndicesToVisit.add(newFileDotPaths.indexOf(newScriptDependencies.get(scriptIndex).importedClasses.get(k)));
                    }
                    //tempMarkedScriptIndices.remove(tempMarkedScriptIndices.indexOf(scriptIndex)); we check for perm marks first
                    // though yeah for memory reasons we maybe probably should remove items from there?
                    
                }
                // INSIDE OF THE TRY BLOCK AT THE END
                importCount++;
            }
            
            
            if (!newScriptContents.isEmpty()) {
                // the next 3 functions are called because TagTreeContextMenu.addAs3ClassActionPerformed() does it.
                ((Tag) NewScriptABCContainer).setModified(true);
                swf.clearAllCache();
                swf.setModified(true);
            }
        }
            
            // ok list of things to do and how to organise it:
            // [me returning here later] ok but *why* exactly would I need ScriptPack instances?
            //   * ohh it's probably because then I can use `pack.abc.replaceScriptPack()`.
            //   * hmm. ok yeah I should add a new method to ActionScript3Parser that returns the info I need for sorting without compiling a dummy script. Then I can
            //     actually compile every script with their actual contents in the sorted order we found, and I won't need to use `pack.abc.replaceScriptPack()`.
            //     it'll also be a cleaner implementation that doesn't waste an extra compilation for every script.
            //   * [ ] Make sure the loop for existing scripts doesn't use `pack.abc.replaceScriptPack()` on new imports!
            //
            // - get a list of all of the new scripts, either as Files or ScriptPacks.
            //   * I might actually need both, or at least the text content + a ScriptPack. the ScriptPacks are more annoying to retrieve because parser.addScript() doesn't
            //     return anything, and having to search for the script I literally just made feels very inefficent. 
            //       * is there another way to do this that would return the ScriptPack without needing to search for it?
            //       * [x] double check how/if the context menu gets the script after creation.
            //          * it manually searches in a few for loops. it needs a tree item instead of a ScriptPack so it's not helpful here.
            //       * could I directly index the script without needing to search the whole list somehow?
            //       * having to compile a blank dummy script first also feels inefficent but I'm not sure if I can easily avoid that? I think I need to because I'm doing
            //         any kind of pre-processing (ie sorting) before I actually compile.
            //           * actually the more I look at it I think I can. I'll have to patch some stuff but I think I can get away with import parsing without a ScriptPack.
            //             though maybe I shouldn't. Yeah it's kinda clunky and slower but I might cause more issues because of how the app is architected.
            //           * can I directly create new instaces of ScriptPack without needing to compile an empty package?
            // - get each script's list of imports and used/defined custom namespaces. I already have some code for this!
            // - sort each script by their dependencies.
            // - compile the new and now sorted scripts.
            //   * do I want to compile them separately or with the rest of the scripts in the main loop?
            //   * compile them separately. I won't be using pack.abc.replaceScriptPack() so it makes more sense to compile them separately.
            
        for (ScriptPack pack : packs) {
            if (CancellableWorker.isInterrupted()) {
                return importCount;
            }
            if (!pack.isSimple) {
                continue;
            }
            try {
                File file = pack.getExportFile(scriptsFolder, new ScriptExportSettings(ScriptExportMode.AS, false, false, false, false, true));
                if (file.exists()) {
                    openable = pack.getOpenable();
                    swf = (openable instanceof SWF) ? (SWF) openable : ((ABC) openable).getSwf();
                    swf.informListeners("importing_as", file.getAbsolutePath());
                    String fileName = file.getAbsolutePath();
                    String txt = Helper.readTextFile(fileName);
                    try {
                        pack.abc.replaceScriptPack(scriptReplacer, pack, txt, dependencies);
                    } catch (As3ScriptReplaceException asre) {
                        for (As3ScriptReplaceExceptionItem item : asre.getExceptionItems()) {
                            logger.log(Level.SEVERE, "%error% on line %line%, column %col%, file: %file%".replace("%error%", item.getMessage()).replace("%line%", Long.toString(item.getLine())).replace("%file%", fileName).replace("%col%", "" + item.getCol()));
                            // I need to be able to see what in the parser actually goes wrong.
                            StringWriter sw = new StringWriter();
                            PrintWriter pw = new PrintWriter(sw);
                            asre.printStackTrace(pw);
                            String arseError = sw.toString();
                            logger.log(Level.SEVERE, arseError);
                        }
                        if (listener != null) {
                            listener.scriptImportError();
                        }
                    } catch (InterruptedException ex) {
                        return importCount;
                    }

                    importCount++;
                    if (listener != null) {
                        listener.scriptImported();
                    }
                }
            } catch (IOException ex) {
                logger.log(Level.SEVERE, null, ex);
            }
        }

        return importCount;
    }
    
    private void recursivelyVisitScriptsForDepthFirstSearch(int visitingScriptIndex, ArrayList<Integer> tempMarkedScriptIndices, ArrayList<Integer> permMarkedScriptIndices, ArrayList<Integer> orderedScriptIndices)
    {
        
    }
    
    private ArrayList<File> recursivelySearchDirForScripts(String scriptsFolder)
    {
        ArrayList<File> allFiles = new ArrayList<>();
        File currentSearchingFolder = new File(scriptsFolder);
        ArrayList<File> unsearchedFolders = new ArrayList<>();
        int searchIterations = 0;
        // TODO: maxSearchIterations should probably be a setting.
        // TODO: raise this number higher than 200
        int maxSearchIterations = 200;
        for(searchIterations = 0; searchIterations < maxSearchIterations; searchIterations++)
        {
            for(File file : currentSearchingFolder.listFiles())
            {
                if(file.isFile() && file.getAbsolutePath().endsWith(".as"))
                {
                    allFiles.add(file);
                }
                if(file.isDirectory())
                {
                    unsearchedFolders.add(file);
                }
            }
            if(unsearchedFolders.isEmpty())
            {
                break;
            }
            else
            {
                currentSearchingFolder = unsearchedFolders.remove(0);
            }
        }
        if(searchIterations >= maxSearchIterations)
        {
            logger.log(Level.WARNING,
                    "Exhausted %i% iterations while trying to recursively search for new scripts.".replace("%i%", String.valueOf(searchIterations))
                    + "\nAny previously non-existent scripts not encountered yet will not be created and imported.");
        }
        return allFiles;
    }
    
    private void addNewClassBeingImported(String scriptDotPath, ABCContainerTag doAbc, SWF swf)
    {
        String pkg = scriptDotPath.contains(".") ? scriptDotPath.substring(0, scriptDotPath.lastIndexOf(".")) : "";
        String classSimpleName = scriptDotPath.contains(".") ? scriptDotPath.substring(scriptDotPath.lastIndexOf(".") + 1) : scriptDotPath;
        String fileName = scriptDotPath.replace(".", "/");
        String[] pkgParts = new String[0];
        if (!pkg.isEmpty()) {
            if (pkg.contains(".")) {
                pkgParts = pkg.split("\\.");
            } else {
                pkgParts = new String[]{pkg};
            }
        }
        try {
            AbcIndexing abcIndex = swf.getAbcIndex();
            abcIndex.selectAbc(doAbc.getABC());
            ActionScript3Parser parser = new ActionScript3Parser(abcIndex);
            DottedChain dc = new DottedChain(pkgParts);
            // due to classes possibly being compiled before their dependencies,
            // scripts are created blank here and then actually compiled later during the main import loop.
            String script = "package " + dc.toPrintableString(new LinkedHashSet<>(), swf, true) + " {"
                        + "public class " + IdentifiersDeobfuscation.printIdentifier(swf, new LinkedHashSet<>(), true, classSimpleName) + " {"
                        + " }"
                        + "}";
            parser.addScript(script, fileName, 0, 0, swf.getDocumentClass(), doAbc.getABC());
        } catch (IOException | InterruptedException | AVM2ParseException | CompilationException ex) {
            Logger.getLogger(AS3ScriptImporter.class.getName()).log(Level.SEVERE, "Error during script compilation", ex);
        }
    }
}
