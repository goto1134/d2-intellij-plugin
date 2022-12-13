// This is a generated file. Not intended for manual editing.
package com.dvd.intellij.d2.lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.dvd.intellij.d2.lang.D2CompositeElement;

public interface D2SubShapeDefinition extends D2CompositeElement {

  @Nullable
  D2BlockDefinition getBlockDefinition();

  @Nullable
  D2InlineShapeDefinition getInlineShapeDefinition();

  @Nullable
  D2LabelDefinition getLabelDefinition();

  @Nullable
  D2ShapeConnection getShapeConnection();

  @NotNull
  List<D2ShapeDefinition> getShapeDefinitionList();

  @Nullable
  D2SubShapeDefinition getSubShapeDefinition();

}
