package borg.trikeshed.placeholder.nars.inference
// some sample nars sentences
/*
$.13 (Calorie-->CompositeUnitOfMeasure). %1.0;.45% {345: o}
$.15 ((PhysicalState-->[Gas,Fluid,Solid,Liquid,Plasma])&&domainSubclass(MakingFn,1,Making)). %1.0;.25% {353: w;x}
$.15 ((PhysicalState-->[Gas,Fluid,Solid,Liquid,Plasma])==>domainSubclass(MakingFn,1,Making)). %1.0;.20% {353: w;x}
$.14 ((({InchMercury}-->UnitOfAtmosphericPressure)&&({equivalentContentClass}-->EquivalenceRelation))==>(Calorie-->CompositeUnitOfMeasure)). %1.0;.10% {353: o;v;z}
$.14 ((PhysicalState-->[Gas,Fluid,Solid,Liquid,Plasma])&&(Law-->DeonticAttribute)). %1.0;.23% {353: l;x}
$.14 (&&,({InchMercury}-->UnitOfAtmosphericPressure),({equivalentContentClass}-->EquivalenceRelation),(Calorie-->CompositeUnitOfMeasure)). %1.0;.11% {353: o;v;z}
$.14 ((PhysicalState-->[Gas,Fluid,Solid,Liquid,Plasma])==>(Law-->DeonticAttribute)). %1.0;.18% {353: l;x}
$.13 ((Law-->DeonticAttribute)&&(Calorie-->CompositeUnitOfMeasure)). %1.0;.20% {353: l;o}
$.13 ((Law-->DeonticAttribute)==>(Calorie-->CompositeUnitOfMeasure)). %1.0;.17% {353: l;o}
$.15 ((agent($1,$2)&&({$1}-->Digesting))==>(&&,overlapsTemporally(WhenFn(#3),WhenFn($1)),agent(#3,$2),({#3}-->Ingesting))). %1.0;.50% {353: E}
$.15 format(EnglishLanguage,ArcTangentFn,"the &%arctan of %1"). %1.0;.50% {353: F}
$.15 (LiquidMixture-->Mixture). %1.0;.50% {353: G}
$.15 ({Becquerel}-->SystemeInternationalUnit). %1.0;.50% {353: H}
$.15 (BeginNodeFn<->InitialNodeFn). %1.0;.50% {353: I}
$.15 (partlyLocated($1,$2)==>(($1-->Physical)&&($2-->Object))). %1.0;.50% {353: J}
$.15 ({before}-->IrreflexiveRelation). %1.0;.50% {353: K}
$.15 (RadiatingInfrared-->RadiatingElectromagnetic). %1.0;.50% {353: L}
$.15 (Translating-->ContentDevelopment). %1.0;.50% {353: M}
$.15 (RealNumberFn($1,$2)==>(($1-->Number)&&($2-->RealNumber))). %1.0;.50% {353: N}
$.20 (?1-->Likely)? {353: 8}
$.15 (domainSubclass(MakingFn,1,Making)&&(depth-->distance)). %1.0;.25% {361: c;w}
$.15 ((depth-->distance)==>domainSubclass(MakingFn,1,Making)). %1.0;.20% {361: c;w}
$.13 (Wet-->SaturationAttribute). %1.0;.45% {361: g}
$.13 (Henry-->SystemeInternationalUnit). %1.0;.45% {361: y}
$.13 (Calorie-->CompositeUnitOfMeasure). %1.0;.45% {361: o}
$.15 ((PhysicalState-->[Gas,Fluid,Solid,Liquid,Plasma])&&domainSubclass(MakingFn,1,Making)). %1.0;.25% {369: w;x}
$.15 ((PhysicalState-->[Gas,Fluid,Solid,Liquid,Plasma])==>domainSubclass(MakingFn,1,Making)). %1.0;.20% {369: w;x}


*/

class n {
}