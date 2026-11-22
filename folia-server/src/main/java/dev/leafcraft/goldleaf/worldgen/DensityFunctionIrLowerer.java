package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.BinaryOperator;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptExpr;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptFunction;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptLut;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptParam;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptStmt;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptType;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.MathIntrinsicKind;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.UnaryOperator;
import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.TilingMode;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.SimpleDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BlendDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.ClampFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.IntervalSelectFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.LerpFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.PowFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RoundFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SplineFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.UnaryFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;
import org.jspecify.annotations.Nullable;

/**
 * Direct typed Java AST lowerer from MC {@code 26.3} {@link DensityFunction} record trees into
 * {@code :bytecode-api}'s pure Tier-1 {@link ConceptModule} SSA IR ({@code goldleaf-ADR-017}).
 *
 * <p>Eliminates ASM {@code .class} bytecode scanning and reflective object-graph scraping on
 * GoldLeaf's Minecraft worldgen hot path. Deduplicates shared DAG nodes (including
 * {@link CacheFunction} and {@link DensityFunctions.HolderHolder}) into shared SSA locals, packs
 * all {@link PerlinNoise} {@code pAdj} permutation tables into one shared LUT read by a single
 * {@code gl_perlin_3d} IR helper, and fails closed cleanly ({@code S-004}) on unsupported or
 * custom {@link DensityFunction} types.</p>
 *
 * @governedBy goldleaf-ADR-017, goldleaf-ADR-004
 * @trace S-004, S-005
 */
public final class DensityFunctionIrLowerer {

    private static final String OWNER = "dev/leafcraft/goldleaf/worldgen/LoweredDensity";
    private static final String LUT_PERLIN_PADJ = "gl_perlin_padj_all";
    private static final String HELPER_PERLIN = "gl_perlin_3d";
    private static final String HELPER_PERLIN_DESC = "(IDDD)F";
    private static final String HELPER_SMEARED_PERLIN = "gl_perlin_3d_smeared";
    private static final String HELPER_SMEARED_PERLIN_DESC = "(IDDDDD)F";
    private static final double HALF_ROUND_OFF = Math.nextDown(1.6777216E7);
    private static final double ROUND_OFF = 33554432.0;

    private DensityFunctionIrLowerer() {
    }

    /**
     * Outcome of lowering a {@link DensityFunction} AST to {@link ConceptModule} IR.
     */
    public record LoweringResult(
        @Nullable ConceptModule module,
        @Nullable String unsupportedReason,
        int loweredNodes,
        int deduplicatedNodes,
        int noiseOctaves
    ) {
        public boolean isSupported() {
            return this.module != null;
        }

        public static LoweringResult unsupported(final String reason) {
            return new LoweringResult(null, reason, 0, 0, 0);
        }
    }

    /**
     * Lowers {@code root} directly into a {@link ConceptModule} with entry signature
     * {@code evalDensity(int bx, int by, int bz) -> float}.
     *
     * @param root                    MC {@code 26.3} density function root
     * @param compileContext          noise compilation context (may be {@code null} for noise-free ASTs)
     * @param moduleName              logical module name
     * @param assumeAlignedCoarseGrid when {@code true}, {@link InterpolatedFunction} passes through
     *                                directly (valid when sampling on the aligned coarse grid)
     */
    public static LoweringResult lower(
        final DensityFunction root,
        final DensityFunction.@Nullable CompileContext compileContext,
        final String moduleName,
        final boolean assumeAlignedCoarseGrid
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(moduleName, "moduleName");
        final LoweringContext ctx = new LoweringContext(compileContext, assumeAlignedCoarseGrid);
        try {
            final ConceptExpr resultExpr = ctx.lowerNode(
                root,
                new ConceptExpr.Var("bx", ConceptType.I32),
                new ConceptExpr.Var("by", ConceptType.I32),
                new ConceptExpr.Var("bz", ConceptType.I32),
                true
            );
            ctx.stmts.add(new ConceptStmt.Return(resultExpr));
            final List<ConceptLut> luts = new ArrayList<>();
            final List<ConceptFunction> helpers = new ArrayList<>();
            if (!ctx.sharedLuts.packedPAdjWords.isEmpty()) {
                luts.add(new ConceptLut(OWNER, LUT_PERLIN_PADJ, LUT_PERLIN_PADJ, ConceptType.I32, ctx.sharedLuts.packedPAdjWords));
                if (ctx.sharedLuts.usesPlainPerlin) {
                    helpers.add(buildPerlin3dHelper());
                }
                if (ctx.sharedLuts.usesSmearedPerlin) {
                    helpers.add(buildSmearedPerlin3dHelper());
                }
            }
            final ConceptFunction entry = new ConceptFunction(
                OWNER,
                "evalDensity",
                "(III)F",
                List.of(
                    new ConceptParam("bx", ConceptType.I32, 0),
                    new ConceptParam("by", ConceptType.I32, 1),
                    new ConceptParam("bz", ConceptType.I32, 2)
                ),
                ConceptType.F32,
                ctx.locals,
                ctx.stmts,
                true
            );
            final ConceptModule module = new ConceptModule(
                moduleName,
                luts,
                helpers,
                entry,
                null,
                null,
                List.of()
            );
            return new LoweringResult(module, null, ctx.loweredNodes, ctx.deduplicatedNodes, ctx.sharedLuts.noiseOctaves);
        } catch (final UnsupportedNodeException ex) {
            return LoweringResult.unsupported(ex.getMessage());
        }
    }

    /**
     * Outcome of split coarse-grid + dense-combiner lowering for
     * {@link io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.BatchLayout#SECTION_PALETTE_FILL_U2}.
     * When {@link #coarseSpec()} is non-null, top-level {@link InterpolatedFunction} subtrees were
     * lifted into coarse helper functions {@code gl_coarse_ch0..K-1} for workgroup {@code __local}
     * trilinear interpolation; otherwise {@link #module()} is a standard dense module.
     */
    public record SplitLoweringResult(
        @Nullable ConceptModule module,
        OpenClBackendEmitter.@Nullable CoarseInterpSpec coarseSpec,
        @Nullable String unsupportedReason,
        int loweredNodes,
        int deduplicatedNodes,
        int noiseOctaves
    ) {
        public boolean isSupported() {
            return this.module != null;
        }

        public boolean hasCoarseInterp() {
            return this.module != null && this.coarseSpec != null;
        }

        public static SplitLoweringResult unsupported(final String reason) {
            return new SplitLoweringResult(null, null, reason, 0, 0, 0);
        }
    }

    /**
     * Lowers {@code root} for {@code SECTION_PALETTE_FILL_U2}, splitting top-level uniform
     * {@link InterpolatedFunction} nodes into coarse helper functions ({@code gl_coarse_ch0..K-1})
     * evaluated on the section's coarse grid in OpenCL {@code __local} memory and trilinearly
     * interpolated into the lightweight dense combiner {@code evalDensity(bx, by, bz, gl_ch_0..K-1)}.
     * Falls back seamlessly to standard dense {@link #lower} when no uniform {@link InterpolatedFunction}
     * channels exist or the coarse {@code __local} budget is exceeded.
     */
    public static SplitLoweringResult lowerSplitForSectionFill(
        final DensityFunction root,
        final DensityFunction.@Nullable CompileContext compileContext,
        final String moduleName
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(moduleName, "moduleName");
        final CoarseChannelDiscovery discovery = new CoarseChannelDiscovery();
        discovery.visit(root);
        if (!discovery.compatible || discovery.channels.isEmpty()) {
            final LoweringResult dense = lower(root, compileContext, moduleName, true);
            return new SplitLoweringResult(
                dense.module(),
                null,
                dense.unsupportedReason(),
                dense.loweredNodes(),
                dense.deduplicatedNodes(),
                dense.noiseOctaves()
            );
        }

        final SharedLutState sharedLuts = new SharedLutState();
        final List<ConceptFunction> helpers = new ArrayList<>();
        final List<String> coarseFnNames = new ArrayList<>(discovery.channels.size());
        final IdentityHashMap<InterpolatedFunction, String> channelParams = new IdentityHashMap<>();
        int totalLoweredNodes = 0;
        int totalDedupNodes = 0;

        try {
            for (int k = 0; k < discovery.channels.size(); k++) {
                final InterpolatedFunction interp = discovery.channels.get(k);
                final String fnName = "gl_coarse_ch" + k;
                final String paramName = "gl_ch_" + k;
                coarseFnNames.add(fnName);
                channelParams.put(interp, paramName);

                final LoweringContext chCtx = new LoweringContext(compileContext, true, sharedLuts, null);
                final ConceptExpr chExpr = chCtx.lowerNode(
                    interp.input(),
                    new ConceptExpr.Var("bx", ConceptType.I32),
                    new ConceptExpr.Var("by", ConceptType.I32),
                    new ConceptExpr.Var("bz", ConceptType.I32),
                    true
                );
                chCtx.stmts.add(new ConceptStmt.Return(chExpr));
                helpers.add(new ConceptFunction(
                    OWNER,
                    fnName,
                    "(III)F",
                    List.of(
                        new ConceptParam("bx", ConceptType.I32, 0),
                        new ConceptParam("by", ConceptType.I32, 1),
                        new ConceptParam("bz", ConceptType.I32, 2)
                    ),
                    ConceptType.F32,
                    chCtx.locals,
                    chCtx.stmts,
                    true
                ));
                totalLoweredNodes += chCtx.loweredNodes;
                totalDedupNodes += chCtx.deduplicatedNodes;
            }

            final LoweringContext entryCtx = new LoweringContext(compileContext, true, sharedLuts, channelParams);
            final ConceptExpr entryExpr = entryCtx.lowerNode(
                root,
                new ConceptExpr.Var("bx", ConceptType.I32),
                new ConceptExpr.Var("by", ConceptType.I32),
                new ConceptExpr.Var("bz", ConceptType.I32),
                true
            );
            entryCtx.stmts.add(new ConceptStmt.Return(entryExpr));
            totalLoweredNodes += entryCtx.loweredNodes;
            totalDedupNodes += entryCtx.deduplicatedNodes;

            final List<ConceptLut> luts = new ArrayList<>();
            final List<ConceptFunction> allHelpers = new ArrayList<>();
            if (!sharedLuts.packedPAdjWords.isEmpty()) {
                luts.add(new ConceptLut(OWNER, LUT_PERLIN_PADJ, LUT_PERLIN_PADJ, ConceptType.I32, sharedLuts.packedPAdjWords));
                if (sharedLuts.usesPlainPerlin) {
                    allHelpers.add(buildPerlin3dHelper());
                }
                if (sharedLuts.usesSmearedPerlin) {
                    allHelpers.add(buildSmearedPerlin3dHelper());
                }
            }
            allHelpers.addAll(helpers);

            final List<ConceptParam> entryParams = new ArrayList<>(3 + coarseFnNames.size());
            entryParams.add(new ConceptParam("bx", ConceptType.I32, 0));
            entryParams.add(new ConceptParam("by", ConceptType.I32, 1));
            entryParams.add(new ConceptParam("bz", ConceptType.I32, 2));
            final StringBuilder desc = new StringBuilder("(III");
            for (int k = 0; k < coarseFnNames.size(); k++) {
                entryParams.add(new ConceptParam("gl_ch_" + k, ConceptType.F32, 3 + k));
                desc.append('F');
            }
            desc.append(")F");

            final ConceptFunction entry = new ConceptFunction(
                OWNER,
                "evalDensity",
                desc.toString(),
                entryParams,
                ConceptType.F32,
                entryCtx.locals,
                entryCtx.stmts,
                true
            );
            final ConceptModule module = new ConceptModule(
                moduleName,
                luts,
                allHelpers,
                entry,
                null,
                null,
                List.of()
            );
            final OpenClBackendEmitter.CoarseInterpSpec coarseSpec = new OpenClBackendEmitter.CoarseInterpSpec(
                discovery.cellSizeXz,
                discovery.cellSizeY,
                coarseFnNames
            );
            return new SplitLoweringResult(
                module,
                coarseSpec,
                null,
                totalLoweredNodes,
                totalDedupNodes,
                sharedLuts.noiseOctaves
            );
        } catch (final UnsupportedNodeException ex) {
            return SplitLoweringResult.unsupported(ex.getMessage());
        }
    }

    private static final class CoarseChannelDiscovery {
        final List<InterpolatedFunction> channels = new ArrayList<>();
        final IdentityHashMap<DensityFunction, Boolean> visited = new IdentityHashMap<>();
        int cellSizeXz = -1;
        int cellSizeY = -1;
        boolean compatible = true;

        void visit(final DensityFunction fn) {
            if (!this.compatible || fn == null || this.visited.put(fn, Boolean.TRUE) != null) {
                return;
            }
            if (fn instanceof DensityFunctions.HolderHolder(Holder<DensityFunction> holder)) {
                visit(holder.value());
            } else if (fn instanceof CacheFunction cache) {
                visit(cache.input());
            } else if (fn instanceof BlendDensityFunction(DensityFunction input)) {
                visit(input);
            } else if (fn instanceof InterpolatedFunction interp) {
                final int cxz = interp.cellSizeXz();
                final int cy = interp.cellSizeY();
                if (cxz <= 0 || (16 % cxz) != 0 || cy <= 0 || (16 % cy) != 0) {
                    this.compatible = false;
                    return;
                }
                if (this.cellSizeXz == -1) {
                    this.cellSizeXz = cxz;
                    this.cellSizeY = cy;
                } else if (this.cellSizeXz != cxz || this.cellSizeY != cy) {
                    this.compatible = false;
                    return;
                }
                this.channels.add(interp);
                final int nx = (16 / cxz) + 1;
                final int ny = (16 / cy) + 1;
                if (this.channels.size() > OpenClBackendEmitter.CoarseInterpSpec.MAX_CHANNELS
                    || nx * ny * nx * this.channels.size() > OpenClBackendEmitter.CoarseInterpSpec.MAX_LOCAL_FLOATS) {
                    this.compatible = false;
                }
            } else if (fn instanceof BinaryFunction bin) {
                visit(bin.left());
                visit(bin.right());
            } else if (fn instanceof UnaryFunction un) {
                visit(un.input());
            } else if (fn instanceof ClampFunction clamp) {
                visit(clamp.input());
            } else if (fn instanceof LerpFunction lerp) {
                visit(lerp.alpha());
                visit(lerp.first());
                visit(lerp.second());
            } else if (fn instanceof PowFunction pow) {
                visit(pow.base());
                visit(pow.exponent());
            } else if (fn instanceof RangeChoiceFunction rc) {
                visit(rc.input());
                visit(rc.whenInRange());
                visit(rc.whenOutOfRange());
            } else if (fn instanceof IntervalSelectFunction sel) {
                visit(sel.input());
                for (DensityFunction f : sel.functions()) {
                    visit(f);
                }
            } else if (fn instanceof RoundFunction rnd) {
                visit(rnd.input());
            }
        }
    }

    private static final class SharedLutState {
        final IdentityHashMap<Noise, List<PerlinLayerSpec>> compiledNoiseCache = new IdentityHashMap<>();
        final List<Number> packedPAdjWords = new ArrayList<>();
        int noiseOctaves;
        boolean usesPlainPerlin;
        boolean usesSmearedPerlin;
    }

    private static final class UnsupportedNodeException extends RuntimeException {
        UnsupportedNodeException(final String message) {
            super(message);
        }
    }

    private static final class LoweringContext {
        private final DensityFunction.@Nullable CompileContext compileContext;
        private final boolean assumeAlignedCoarseGrid;
        private final SharedLutState sharedLuts;
        private final @Nullable IdentityHashMap<InterpolatedFunction, String> coarseChannelParams;
        private final Map<String, ConceptType> locals = new LinkedHashMap<>();
        private final List<ConceptStmt> stmts = new ArrayList<>();
        private final IdentityHashMap<DensityFunction, ConceptExpr> dagMemo = new IdentityHashMap<>();
        private int tempCounter;
        private int loweredNodes;
        private int deduplicatedNodes;

        LoweringContext(final DensityFunction.@Nullable CompileContext compileContext, final boolean assumeAlignedCoarseGrid) {
            this(compileContext, assumeAlignedCoarseGrid, new SharedLutState(), null);
        }

        LoweringContext(
            final DensityFunction.@Nullable CompileContext compileContext,
            final boolean assumeAlignedCoarseGrid,
            final SharedLutState sharedLuts,
            final @Nullable IdentityHashMap<InterpolatedFunction, String> coarseChannelParams
        ) {
            this.compileContext = compileContext;
            this.assumeAlignedCoarseGrid = assumeAlignedCoarseGrid;
            this.sharedLuts = sharedLuts;
            this.coarseChannelParams = coarseChannelParams;
        }

        private String nextTemp(final String prefix) {
            return prefix + (this.tempCounter++);
        }

        private ConceptExpr.Var bindLocal(final String prefix, final ConceptType type, final ConceptExpr expr) {
            final String name = nextTemp(prefix);
            this.locals.put(name, type);
            this.stmts.add(new ConceptStmt.Assign(name, type, expr));
            return new ConceptExpr.Var(name, type);
        }

        private ConceptExpr lowerNode(
            final DensityFunction fn,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            if (canMemoize) {
                final ConceptExpr cached = this.dagMemo.get(fn);
                if (cached != null) {
                    this.deduplicatedNodes++;
                    return cached;
                }
            }
            this.loweredNodes++;

            final ConceptExpr lowered;
            if (fn instanceof DensityFunctions.HolderHolder(Holder<DensityFunction> holder)) {
                lowered = lowerNode(holder.value(), xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof CacheFunction(DensityFunction input)) {
                final ConceptExpr inner = lowerNode(input, xExpr, yExpr, zExpr, canMemoize);
                lowered = inner instanceof ConceptExpr.FloatConst || inner instanceof ConceptExpr.Var
                    ? inner
                    : bindLocal("c_", ConceptType.F32, inner);
            } else if (fn instanceof ConstantFunction(float value)) {
                lowered = new ConceptExpr.FloatConst(value);
            } else if (fn instanceof GradientFunction grad) {
                lowered = lowerGradient(grad, xExpr, yExpr, zExpr);
            } else if (fn instanceof BinaryFunction bin) {
                lowered = lowerBinary(bin, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof UnaryFunction un) {
                lowered = lowerUnary(un, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof ClampFunction clamp) {
                final ConceptExpr in = lowerNode(clamp.input(), xExpr, yExpr, zExpr, canMemoize);
                lowered = fmin(fmax(in, f32(clamp.min())), f32(clamp.max()));
            } else if (fn instanceof LerpFunction lerp) {
                lowered = lowerLerp(lerp, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof PowFunction pow) {
                lowered = lowerPow(pow, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof RangeChoiceFunction rc) {
                lowered = lowerRangeChoice(rc, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof IntervalSelectFunction sel) {
                lowered = lowerIntervalSelect(sel, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof RoundFunction rnd) {
                lowered = lowerRound(rnd, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof SliceFunction slice) {
                final ConceptExpr coord = i32(slice.coordinate());
                lowered = switch (slice.axis()) {
                    case X -> lowerNode(slice.input(), coord, yExpr, zExpr, false);
                    case Y -> lowerNode(slice.input(), xExpr, coord, zExpr, false);
                    case Z -> lowerNode(slice.input(), xExpr, yExpr, coord, false);
                };
            } else if (fn instanceof SplineFunction(CubicSpline<SplineFunction.Coordinate> spline)) {
                lowered = lowerSpline(spline, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof InterpolatedFunction interp) {
                if (canMemoize && this.coarseChannelParams != null) {
                    final String paramName = this.coarseChannelParams.get(interp);
                    if (paramName != null) {
                        lowered = new ConceptExpr.Var(paramName, ConceptType.F32);
                    } else if (!this.assumeAlignedCoarseGrid) {
                        throw new UnsupportedNodeException("InterpolatedFunction requires aligned coarse-grid dispatch");
                    } else {
                        lowered = lowerNode(interp.input(), xExpr, yExpr, zExpr, canMemoize);
                    }
                } else if (!this.assumeAlignedCoarseGrid) {
                    throw new UnsupportedNodeException("InterpolatedFunction requires aligned coarse-grid dispatch");
                } else {
                    lowered = lowerNode(interp.input(), xExpr, yExpr, zExpr, canMemoize);
                }
            } else if (fn instanceof NoiseFunction noiseFn) {
                lowered = lowerNoiseFunction(noiseFn, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof ShiftNoiseFunction shiftFn) {
                lowered = lowerShiftNoise(shiftFn, xExpr, yExpr, zExpr);
            } else if (fn instanceof SimpleDensityFunction simpleFn) {
                lowered = switch (simpleFn) {
                    case BLEND_ALPHA -> f32(1.0f);
                    case BLEND_OFFSET, BEARDIFIER -> f32(0.0f);
                };
            } else if (fn instanceof BlendDensityFunction(DensityFunction input)) {
                lowered = lowerNode(input, xExpr, yExpr, zExpr, canMemoize);
            } else if (fn instanceof BlendedNoise blendedNoise) {
                lowered = lowerBlendedNoise(blendedNoise, xExpr, yExpr, zExpr);
            } else {
                throw new UnsupportedNodeException("Unsupported DensityFunction: " + fn.getClass().getName());
            }

            if (canMemoize) {
                final ConceptExpr memoVal = (lowered instanceof ConceptExpr.FloatConst || lowered instanceof ConceptExpr.Var)
                    ? lowered
                    : bindLocal("n_", ConceptType.F32, lowered);
                this.dagMemo.put(fn, memoVal);
                return memoVal;
            }
            return lowered;
        }

        private ConceptExpr lowerGradient(
            final GradientFunction grad,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr
        ) {
            final ConceptExpr coord = switch (grad.axis()) {
                case X -> xExpr;
                case Y -> yExpr;
                case Z -> zExpr;
            };
            final int coordRange = grad.toCoordinate() - grad.fromCoordinate();
            final float factor = (grad.toValue() - grad.fromValue()) / coordRange;
            if (grad.tiling() == TilingMode.CLAMP_TO_EDGE) {
                final int minC = Math.min(grad.fromCoordinate(), grad.toCoordinate());
                final int maxC = Math.max(grad.fromCoordinate(), grad.toCoordinate());
                final ConceptExpr clamped = imin(imax(coord, i32(minC)), i32(maxC));
                final ConceptExpr rel = subI32(clamped, i32(grad.fromCoordinate()));
                return addF32(f32(grad.fromValue()), mulF32(castF32(rel), f32(factor)));
            }
            final ConceptExpr rel = bindLocal("gr_", ConceptType.I32, subI32(coord, i32(grad.fromCoordinate())));
            final ConceptExpr rangeExpr = i32(coordRange);
            // Java Math.floorDiv(a, b) exact lowering via remainder sign adjustment
            final ConceptExpr q = bindLocal("gq_", ConceptType.I32, divI32(rel, rangeExpr));
            final ConceptExpr r = bindLocal("gm_", ConceptType.I32, subI32(rel, mulI32(q, rangeExpr)));
            final ConceptExpr needAdj = new ConceptExpr.BinaryOp(
                BinaryOperator.LOGICAL_AND,
                new ConceptExpr.BinaryOp(BinaryOperator.NE, r, i32(0), ConceptType.BOOL),
                new ConceptExpr.BinaryOp(
                    BinaryOperator.LT,
                    new ConceptExpr.BinaryOp(BinaryOperator.BIT_XOR, rel, rangeExpr, ConceptType.I32),
                    i32(0),
                    ConceptType.BOOL
                ),
                ConceptType.BOOL
            );
            final ConceptExpr tileIdx = bindLocal(
                "gt_",
                ConceptType.I32,
                new ConceptExpr.Ternary(needAdj, subI32(q, i32(1)), q, ConceptType.I32)
            );
            final ConceptExpr localC = bindLocal("gl_", ConceptType.I32, subI32(rel, mulI32(tileIdx, rangeExpr)));
            if (grad.tiling() == TilingMode.REPEAT) {
                return addF32(f32(grad.fromValue()), mulF32(castF32(localC), f32(factor)));
            }
            final ConceptExpr isEven = new ConceptExpr.BinaryOp(
                BinaryOperator.EQ,
                new ConceptExpr.BinaryOp(BinaryOperator.BIT_AND, tileIdx, i32(1), ConceptType.I32),
                i32(0),
                ConceptType.BOOL
            );
            final ConceptExpr effective = new ConceptExpr.Ternary(
                isEven,
                localC,
                subI32(rangeExpr, localC),
                ConceptType.I32
            );
            return addF32(f32(grad.fromValue()), mulF32(castF32(effective), f32(factor)));
        }

        private ConceptExpr lowerBinary(
            final BinaryFunction bin,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            if (bin.type() == BinaryFunction.Type.MIN) {
                final Interval lr = bin.left().range();
                final Interval rr = bin.right().range();
                if (lr.max() < rr.min()) {
                    return lowerNode(bin.left(), xExpr, yExpr, zExpr, canMemoize);
                }
                if (rr.max() < lr.min()) {
                    return lowerNode(bin.right(), xExpr, yExpr, zExpr, canMemoize);
                }
            } else if (bin.type() == BinaryFunction.Type.MAX) {
                final Interval lr = bin.left().range();
                final Interval rr = bin.right().range();
                if (lr.min() > rr.max()) {
                    return lowerNode(bin.left(), xExpr, yExpr, zExpr, canMemoize);
                }
                if (rr.min() > lr.max()) {
                    return lowerNode(bin.right(), xExpr, yExpr, zExpr, canMemoize);
                }
            }
            final ConceptExpr left = lowerNode(bin.left(), xExpr, yExpr, zExpr, canMemoize);
            final ConceptExpr right = lowerNode(bin.right(), xExpr, yExpr, zExpr, canMemoize);
            return switch (bin.type()) {
                case ADD -> addF32(left, right);
                case SUB -> subF32(left, right);
                case MUL -> mulF32(left, right);
                case DIV -> divF32(left, right);
                case MIN -> fmin(left, right);
                case MAX -> fmax(left, right);
            };
        }

        private ConceptExpr lowerUnary(
            final UnaryFunction un,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            final ConceptExpr in = lowerNode(un.input(), xExpr, yExpr, zExpr, canMemoize);
            return switch (un.type()) {
                case ABS -> new ConceptExpr.MathIntrinsic(MathIntrinsicKind.ABS, List.of(in), ConceptType.F32);
                case SQUARE -> {
                    final ConceptExpr v = ensureVar(in, "sq_");
                    yield mulF32(v, v);
                }
                case CUBE -> {
                    final ConceptExpr v = ensureVar(in, "cb_");
                    yield mulF32(mulF32(v, v), v);
                }
                case SQRT -> new ConceptExpr.MathIntrinsic(MathIntrinsicKind.SQRT, List.of(in), ConceptType.F32);
                case HALF_NEGATIVE -> {
                    final ConceptExpr v = ensureVar(in, "hn_");
                    yield new ConceptExpr.Ternary(gtF32(v, f32(0.0f)), v, mulF32(v, f32(0.5f)), ConceptType.F32);
                }
                case QUARTER_NEGATIVE -> {
                    final ConceptExpr v = ensureVar(in, "qn_");
                    yield new ConceptExpr.Ternary(gtF32(v, f32(0.0f)), v, mulF32(v, f32(0.25f)), ConceptType.F32);
                }
                case RECIPROCAL -> divF32(f32(1.0f), in);
                case NEGATE -> new ConceptExpr.UnaryOp(UnaryOperator.NEG, in, ConceptType.F32);
                case SQUEEZE -> {
                    final ConceptExpr c = bindLocal("sqz_", ConceptType.F32, fmin(fmax(in, f32(-1.0f)), f32(1.0f)));
                    yield subF32(mulF32(c, f32(0.5f)), divF32(mulF32(mulF32(c, c), c), f32(24.0f)));
                }
                case LOG -> new ConceptExpr.MathIntrinsic(MathIntrinsicKind.LOG, List.of(in), ConceptType.F32);
                case SIGN -> {
                    final ConceptExpr v = ensureVar(in, "sg_");
                    yield new ConceptExpr.Ternary(
                        gtF32(v, f32(0.0f)),
                        f32(1.0f),
                        new ConceptExpr.Ternary(ltF32(v, f32(0.0f)), f32(-1.0f), f32(0.0f), ConceptType.F32),
                        ConceptType.F32
                    );
                }
            };
        }

        private ConceptExpr lowerLerp(
            final LerpFunction lerp,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            final ConceptExpr a = ensureVar(lowerNode(lerp.alpha(), xExpr, yExpr, zExpr, canMemoize), "la_");
            final ConceptExpr first = ensureVar(lowerNode(lerp.first(), xExpr, yExpr, zExpr, canMemoize), "lf_");
            final ConceptExpr second = ensureVar(lowerNode(lerp.second(), xExpr, yExpr, zExpr, canMemoize), "ls_");
            final ConceptExpr blended = addF32(first, mulF32(a, subF32(second, first)));
            return new ConceptExpr.Ternary(
                eqF32(a, f32(0.0f)),
                first,
                new ConceptExpr.Ternary(eqF32(a, f32(1.0f)), second, blended, ConceptType.F32),
                ConceptType.F32
            );
        }

        private ConceptExpr lowerPow(
            final PowFunction pow,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            final ConceptExpr base = ensureVar(lowerNode(pow.base(), xExpr, yExpr, zExpr, canMemoize), "pb_");
            if (pow.exponent() instanceof ConstantFunction(float expVal)) {
                final float absExp = Math.abs(expVal);
                ConceptExpr special = null;
                if (absExp == 0.5f) {
                    special = new ConceptExpr.MathIntrinsic(MathIntrinsicKind.SQRT, List.of(base), ConceptType.F32);
                } else if (absExp == 1.0f) {
                    special = base;
                } else if (absExp == 2.0f) {
                    special = mulF32(base, base);
                } else if (absExp == 3.0f) {
                    special = mulF32(mulF32(base, base), base);
                }
                if (special != null) {
                    return expVal >= 0.0f ? special : divF32(f32(1.0f), special);
                }
            }
            final ConceptExpr exp = lowerNode(pow.exponent(), xExpr, yExpr, zExpr, canMemoize);
            return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.POW, List.of(base, exp), ConceptType.F32);
        }

        private ConceptExpr lowerRangeChoice(
            final RangeChoiceFunction rc,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            final ConceptExpr in = ensureVar(lowerNode(rc.input(), xExpr, yExpr, zExpr, canMemoize), "rc_");
            final ConceptExpr inRange = lowerNode(rc.whenInRange(), xExpr, yExpr, zExpr, canMemoize);
            final ConceptExpr outRange = lowerNode(rc.whenOutOfRange(), xExpr, yExpr, zExpr, canMemoize);
            final ConceptExpr cond = new ConceptExpr.BinaryOp(
                BinaryOperator.LOGICAL_AND,
                geF32(in, f32(rc.minInclusive())),
                ltF32(in, f32(rc.maxExclusive())),
                ConceptType.BOOL
            );
            return new ConceptExpr.Ternary(cond, inRange, outRange, ConceptType.F32);
        }

        private ConceptExpr lowerIntervalSelect(
            final IntervalSelectFunction sel,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            final ConceptExpr in = ensureVar(lowerNode(sel.input(), xExpr, yExpr, zExpr, canMemoize), "is_");
            final int n = sel.functions().size();
            ConceptExpr expr = lowerNode(sel.functions().get(n - 1), xExpr, yExpr, zExpr, canMemoize);
            for (int i = n - 2; i >= 0; i--) {
                final ConceptExpr branch = lowerNode(sel.functions().get(i), xExpr, yExpr, zExpr, canMemoize);
                expr = new ConceptExpr.Ternary(ltF32(in, f32(sel.thresholds().getFloat(i))), branch, expr, ConceptType.F32);
            }
            return expr;
        }

        private ConceptExpr lowerRound(
            final RoundFunction rnd,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            final ConceptExpr in = ensureVar(lowerNode(rnd.input(), xExpr, yExpr, zExpr, canMemoize), "rn_");
            if (rnd.multiple() instanceof ConstantFunction(float multVal) && multVal == 1.0f) {
                return applyRoundToInteger(in, rnd.type());
            }
            final ConceptExpr m = ensureVar(lowerNode(rnd.multiple(), xExpr, yExpr, zExpr, canMemoize), "rm_");
            final ConceptExpr scaled = ensureVar(divF32(in, m), "rs_");
            final ConceptExpr rounded = mulF32(applyRoundToInteger(scaled, rnd.type()), m);
            return new ConceptExpr.Ternary(eqF32(m, f32(0.0f)), in, rounded, ConceptType.F32);
        }

        private ConceptExpr applyRoundToInteger(final ConceptExpr val, final RoundFunction.Type type) {
            return switch (type) {
                case FLOOR -> new ConceptExpr.MathIntrinsic(MathIntrinsicKind.FLOOR, List.of(val), ConceptType.F32);
                case CEIL -> new ConceptExpr.MathIntrinsic(MathIntrinsicKind.CEIL, List.of(val), ConceptType.F32);
                case ROUND -> new ConceptExpr.MathIntrinsic(MathIntrinsicKind.FLOOR, List.of(addF32(val, f32(0.5f))), ConceptType.F32);
                case TRUNCATE -> new ConceptExpr.Ternary(
                    gtF32(val, f32(0.0f)),
                    new ConceptExpr.MathIntrinsic(MathIntrinsicKind.FLOOR, List.of(val), ConceptType.F32),
                    new ConceptExpr.MathIntrinsic(MathIntrinsicKind.CEIL, List.of(val), ConceptType.F32),
                    ConceptType.F32
                );
            };
        }

        private ConceptExpr lowerSpline(
            final CubicSpline<SplineFunction.Coordinate> spline,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            if (spline instanceof CubicSpline.Constant<SplineFunction.Coordinate>(float value)) {
                return f32(value);
            }
            if (!(spline instanceof CubicSpline.Multipoint<SplineFunction.Coordinate> mp)) {
                throw new UnsupportedNodeException("Unsupported CubicSpline: " + spline.getClass().getName());
            }
            final ConceptExpr coord = ensureVar(lowerNode(mp.coordinate().function(), xExpr, yExpr, zExpr, canMemoize), "sp_");
            final float[] locs = mp.locations();
            final float[] derivs = mp.derivatives();
            final List<CubicSpline<SplineFunction.Coordinate>> vals = mp.values();
            final int last = locs.length - 1;

            final List<ConceptExpr> loweredVals = new ArrayList<>(vals.size());
            for (CubicSpline<SplineFunction.Coordinate> child : vals) {
                loweredVals.add(ensureVar(lowerSpline(child, xExpr, yExpr, zExpr, canMemoize), "sv_"));
            }

            // Right linear extension: coord >= locs[last]
            ConceptExpr acc = linearExtendExpr(coord, locs[last], loweredVals.get(last), derivs[last]);
            // Interior segments [i, i + 1]
            for (int i = last - 1; i >= 0; i--) {
                final float x1 = locs[i];
                final float x2 = locs[i + 1];
                final float dx = x2 - x1;
                final float invDx = 1.0f / dx;
                final ConceptExpr t = bindLocal("st_", ConceptType.F32, mulF32(subF32(coord, f32(x1)), f32(invDx)));
                final ConceptExpr y1 = loweredVals.get(i);
                final ConceptExpr y2 = loweredVals.get(i + 1);
                final ConceptExpr dy = bindLocal("sdy_", ConceptType.F32, subF32(y2, y1));
                final ConceptExpr a = bindLocal("sa_", ConceptType.F32, subF32(f32(derivs[i] * dx), dy));
                final ConceptExpr b = bindLocal("sb_", ConceptType.F32, addF32(f32(-derivs[i + 1] * dx), dy));
                final ConceptExpr lin = addF32(y1, mulF32(t, dy));
                final ConceptExpr ab = addF32(a, mulF32(t, subF32(b, a)));
                final ConceptExpr segVal = addF32(lin, mulF32(mulF32(t, subF32(f32(1.0f), t)), ab));
                acc = new ConceptExpr.Ternary(ltF32(coord, f32(x2)), segVal, acc, ConceptType.F32);
            }
            // Left linear extension: coord < locs[0]
            final ConceptExpr leftExt = linearExtendExpr(coord, locs[0], loweredVals.get(0), derivs[0]);
            return new ConceptExpr.Ternary(ltF32(coord, f32(locs[0])), leftExt, acc, ConceptType.F32);
        }

        private ConceptExpr linearExtendExpr(final ConceptExpr input, final float loc, final ConceptExpr val, final float deriv) {
            if (deriv == 0.0f) {
                return val;
            }
            return addF32(val, mulF32(f32(deriv), subF32(input, f32(loc))));
        }

        private ConceptExpr lowerNoiseFunction(
            final NoiseFunction noiseFn,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr,
            final boolean canMemoize
        ) {
            final List<PerlinLayerSpec> layers = resolveNoiseLayers(noiseFn.noise());
            ConceptExpr nx = mulF64(castF64(xExpr), f64(noiseFn.xzScale()));
            ConceptExpr ny = mulF64(castF64(yExpr), f64(noiseFn.yScale()));
            ConceptExpr nz = mulF64(castF64(zExpr), f64(noiseFn.xzScale()));

            if (!isZeroConst(noiseFn.shiftX())) {
                nx = addF64(nx, castF64(lowerNode(noiseFn.shiftX(), xExpr, yExpr, zExpr, canMemoize)));
            }
            if (!isZeroConst(noiseFn.shiftY())) {
                ny = addF64(ny, castF64(lowerNode(noiseFn.shiftY(), xExpr, yExpr, zExpr, canMemoize)));
            }
            if (!isZeroConst(noiseFn.shiftZ())) {
                nz = addF64(nz, castF64(lowerNode(noiseFn.shiftZ(), xExpr, yExpr, zExpr, canMemoize)));
            }
            return emitPerlinStackSum(layers, nx, ny, nz, 1.0f);
        }

        private ConceptExpr lowerShiftNoise(
            final ShiftNoiseFunction shiftFn,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr
        ) {
            final List<PerlinLayerSpec> layers = resolveNoiseLayers(shiftFn.offsetNoise());
            if (shiftFn instanceof ShiftNoiseFunction.Shift) {
                return emitPerlinStackSum(
                    layers,
                    mulF64(castF64(xExpr), f64(0.25)),
                    mulF64(castF64(yExpr), f64(0.25)),
                    mulF64(castF64(zExpr), f64(0.25)),
                    4.0f
                );
            }
            if (shiftFn instanceof ShiftNoiseFunction.ShiftA) {
                return emitPerlinStackSum(
                    layers,
                    mulF64(castF64(xExpr), f64(0.25)),
                    f64(0.0),
                    mulF64(castF64(zExpr), f64(0.25)),
                    4.0f
                );
            }
            if (shiftFn instanceof ShiftNoiseFunction.ShiftB) {
                return emitPerlinStackSum(
                    layers,
                    mulF64(castF64(zExpr), f64(0.25)),
                    mulF64(castF64(xExpr), f64(0.25)),
                    f64(0.0),
                    4.0f
                );
            }
            throw new UnsupportedNodeException("Unsupported ShiftNoiseFunction: " + shiftFn.getClass().getName());
        }

        private ConceptExpr lowerBlendedNoise(
            final BlendedNoise blendedNoise,
            final ConceptExpr xExpr,
            final ConceptExpr yExpr,
            final ConceptExpr zExpr
        ) {
            if (this.compileContext == null) {
                throw new UnsupportedNodeException("CompileContext required to lower BlendedNoise");
            }
            final BlendedNoise.FbmSet fbms = blendedNoise.createFbmSet(this.compileContext.createRandom(BlendedNoise.NOISE_SEED));
            final double xzMultiplier = 684.412 * blendedNoise.xzScale();
            final double yMultiplier = 684.412 * blendedNoise.yScale();

            final List<PerlinLayerSpec> minLayers = new ArrayList<>();
            collectPerlinLayers(fbms.minLimitNoise(), 1.0, 1.0f, minLayers);
            final List<PerlinLayerSpec> maxLayers = new ArrayList<>();
            collectPerlinLayers(fbms.maxLimitNoise(), 1.0, 1.0f, maxLayers);
            final List<PerlinLayerSpec> mainLayers = new ArrayList<>();
            collectPerlinLayers(fbms.mainNoise(), 1.0, 1.0f, mainLayers);

            final ConceptExpr xD = castF64(xExpr);
            final ConceptExpr yD = castF64(yExpr);
            final ConceptExpr zD = castF64(zExpr);

            final ConceptExpr mainVal = emitPerlinStackSum(
                mainLayers,
                mulF64(xD, f64(xzMultiplier / blendedNoise.xzFactor())),
                mulF64(yD, f64(yMultiplier / blendedNoise.yFactor())),
                mulF64(zD, f64(xzMultiplier / blendedNoise.xzFactor())),
                1.0f
            );
            final ConceptExpr choice = bindLocal(
                "bn_c_",
                ConceptType.F32,
                fmin(fmax(addF32(mainVal, f32(0.5f)), f32(0.0f)), f32(1.0f))
            );
            final ConceptExpr lx = mulF64(xD, f64(xzMultiplier));
            final ConceptExpr ly = mulF64(yD, f64(yMultiplier));
            final ConceptExpr lz = mulF64(zD, f64(xzMultiplier));
            final ConceptExpr minVal = bindLocal("bn_lo_", ConceptType.F32, emitPerlinStackSum(minLayers, lx, ly, lz, 1.0f));
            final ConceptExpr maxVal = bindLocal("bn_hi_", ConceptType.F32, emitPerlinStackSum(maxLayers, lx, ly, lz, 1.0f));
            return lerpF32(choice, minVal, maxVal);
        }

        private ConceptExpr emitPerlinStackSum(
            final List<PerlinLayerSpec> layers,
            final ConceptExpr nx,
            final ConceptExpr ny,
            final ConceptExpr nz,
            final float outerScale
        ) {
            if (layers.isEmpty()) {
                return f32(0.0f);
            }
            final ConceptExpr bx = bindLocal("nx_", ConceptType.F64, nx);
            final ConceptExpr by = bindLocal("ny_", ConceptType.F64, ny);
            final ConceptExpr bz = bindLocal("nz_", ConceptType.F64, nz);
            ConceptExpr sum = null;
            for (PerlinLayerSpec layer : layers) {
                final ConceptExpr sx = addF64(wrapCoord(mulF64(bx, f64(layer.frequency))), f64(layer.offsetX));
                final ConceptExpr origY = mulF64(by, f64(layer.frequency));
                final ConceptExpr sy = addF64(wrapCoord(origY), f64(layer.offsetY));
                final ConceptExpr sz = addF64(wrapCoord(mulF64(bz, f64(layer.frequency))), f64(layer.offsetZ));
                final ConceptExpr sample;
                if (layer.fudgeYScale > 0.0) {
                    sample = new ConceptExpr.Call(
                        OWNER,
                        HELPER_SMEARED_PERLIN,
                        HELPER_SMEARED_PERLIN_DESC,
                        List.of(i32(layer.permOffset), sx, sy, sz, origY, f64(layer.fudgeYScale)),
                        ConceptType.F32
                    );
                } else {
                    sample = new ConceptExpr.Call(
                        OWNER,
                        HELPER_PERLIN,
                        HELPER_PERLIN_DESC,
                        List.of(i32(layer.permOffset), sx, sy, sz),
                        ConceptType.F32
                    );
                }
                final ConceptExpr weighted = mulF32(f32(layer.amplitude), sample);
                sum = (sum == null) ? weighted : addF32(sum, weighted);
            }
            return outerScale == 1.0f ? sum : mulF32(sum, f32(outerScale));
        }

        private ConceptExpr wrapCoord(final ConceptExpr coord) {
            final ConceptExpr v = bindLocal("wc_", ConceptType.F64, coord);
            final ConceptExpr inBounds = new ConceptExpr.BinaryOp(
                BinaryOperator.LOGICAL_AND,
                new ConceptExpr.BinaryOp(BinaryOperator.GE, v, f64(-HALF_ROUND_OFF), ConceptType.BOOL),
                new ConceptExpr.BinaryOp(BinaryOperator.LT, v, f64(HALF_ROUND_OFF), ConceptType.BOOL),
                ConceptType.BOOL
            );
            final ConceptExpr wrapped = subF64(
                v,
                mulF64(
                    new ConceptExpr.MathIntrinsic(
                        MathIntrinsicKind.FLOOR,
                        List.of(addF64(divF64(v, f64(ROUND_OFF)), f64(0.5))),
                        ConceptType.F64
                    ),
                    f64(ROUND_OFF)
                )
            );
            return new ConceptExpr.Ternary(inBounds, v, wrapped, ConceptType.F64);
        }

        private List<PerlinLayerSpec> resolveNoiseLayers(final Holder<NormalNoise> holder) {
            if (this.compileContext == null) {
                throw new UnsupportedNodeException("CompileContext required to lower NoiseFunction");
            }
            final Noise noise = this.compileContext.createNoiseSampler(holder);
            final List<PerlinLayerSpec> cached = this.sharedLuts.compiledNoiseCache.get(noise);
            if (cached != null) {
                return cached;
            }
            final List<PerlinLayerSpec> layers = new ArrayList<>();
            collectPerlinLayers(noise, 1.0, 1.0f, layers);
            this.sharedLuts.compiledNoiseCache.put(noise, layers);
            return layers;
        }

        private void collectPerlinLayers(
            final Noise noise,
            final double freqScale,
            final float ampScale,
            final List<PerlinLayerSpec> out
        ) {
            if (noise instanceof SmearedPerlinNoise smeared) {
                final int permOffset = appendPAdjTable(smeared.moonrise$getPAdj());
                final double ox = smeared.paper$getOffsetX();
                final double oy = smeared.paper$getOffsetY();
                final double oz = smeared.paper$getOffsetZ();
                out.add(new PerlinLayerSpec(permOffset, freqScale, ampScale, ox, oy, oz, smeared.paper$getFudgeYScale()));
                this.sharedLuts.usesSmearedPerlin = true;
                this.sharedLuts.noiseOctaves++;
                return;
            }
            if (noise instanceof PerlinNoise perlin) {
                final int permOffset = appendPAdjTable(perlin.moonrise$getPAdj());
                final double ox = perlin.paper$getOffsetX();
                final double oy = perlin.paper$getOffsetY();
                final double oz = perlin.paper$getOffsetZ();
                out.add(new PerlinLayerSpec(permOffset, freqScale, ampScale, ox, oy, oz, 0.0));
                this.sharedLuts.usesPlainPerlin = true;
                this.sharedLuts.noiseOctaves++;
                return;
            }
            if (noise instanceof NoiseStack stack) {
                final int layerCount = stack.paper$getLayerCount();
                for (int i = 0; i < layerCount; i++) {
                    final Noise child = stack.paper$getLayerNoise(i);
                    final double freq = stack.paper$getLayerFrequency(i);
                    final float amp = stack.paper$getLayerAmplitude(i);
                    collectPerlinLayers(child, freqScale * freq, ampScale * amp, out);
                }
                return;
            }
            throw new UnsupportedNodeException("Unsupported Noise implementation: " + noise.getClass().getName());
        }

        private int appendPAdjTable(final char[] pAdj) {
            final int offset = this.sharedLuts.packedPAdjWords.size();
            for (int i = 0; i < 256; i++) {
                this.sharedLuts.packedPAdjWords.add((int) pAdj[i]);
            }
            return offset;
        }

        private ConceptExpr ensureVar(final ConceptExpr expr, final String prefix) {
            return (expr instanceof ConceptExpr.Var || expr instanceof ConceptExpr.FloatConst)
                ? expr
                : bindLocal(prefix, ConceptType.F32, expr);
        }
    }

    private record PerlinLayerSpec(
        int permOffset,
        double frequency,
        float amplitude,
        double offsetX,
        double offsetY,
        double offsetZ,
        double fudgeYScale
    ) {
    }

    /**
     * Emits the shared {@code gl_perlin_3d_smeared(int permOffset, double x, double y, double z, double origY, double fudgeYScale) -> float}
     * IR helper matching MC {@code 26.3}'s {@link SmearedPerlinNoise#get} 1:1.
     */
    private static ConceptFunction buildSmearedPerlin3dHelper() {
        final Map<String, ConceptType> locals = new LinkedHashMap<>();
        final List<ConceptStmt> stmts = new ArrayList<>();

        final ConceptExpr permOff = new ConceptExpr.Var("permOff", ConceptType.I32);
        final ConceptExpr x = new ConceptExpr.Var("x", ConceptType.F64);
        final ConceptExpr y = new ConceptExpr.Var("y", ConceptType.F64);
        final ConceptExpr z = new ConceptExpr.Var("z", ConceptType.F64);
        final ConceptExpr origY = new ConceptExpr.Var("origY", ConceptType.F64);
        final ConceptExpr fudgeYScale = new ConceptExpr.Var("fudgeYScale", ConceptType.F64);

        final ConceptExpr fxD = assign(locals, stmts, "fxD", ConceptType.F64, floorF64(x));
        final ConceptExpr fyD = assign(locals, stmts, "fyD", ConceptType.F64, floorF64(y));
        final ConceptExpr fzD = assign(locals, stmts, "fzD", ConceptType.F64, floorF64(z));

        final ConceptExpr fx = assign(locals, stmts, "fx", ConceptType.I32, castI32(fxD));
        final ConceptExpr fy = assign(locals, stmts, "fy", ConceptType.I32, castI32(fyD));
        final ConceptExpr fz = assign(locals, stmts, "fz", ConceptType.I32, castI32(fzD));

        final ConceptExpr rx = assign(locals, stmts, "rx", ConceptType.F32, castF32(subF64(x, fxD)));
        final ConceptExpr ryD = assign(locals, stmts, "ryD", ConceptType.F64, subF64(y, fyD));
        final ConceptExpr ryAlpha = assign(locals, stmts, "ryAlpha", ConceptType.F32, castF32(ryD));
        final ConceptExpr rz = assign(locals, stmts, "rz", ConceptType.F32, castF32(subF64(z, fzD)));

        final ConceptExpr useOrigY = new ConceptExpr.BinaryOp(
            BinaryOperator.LOGICAL_AND,
            new ConceptExpr.BinaryOp(BinaryOperator.GE, origY, f64(0.0), ConceptType.BOOL),
            new ConceptExpr.BinaryOp(BinaryOperator.LT, origY, ryD, ConceptType.BOOL),
            ConceptType.BOOL
        );
        final ConceptExpr fudgeLimit = assign(
            locals,
            stmts,
            "fudgeLimit",
            ConceptType.F64,
            new ConceptExpr.Ternary(useOrigY, origY, ryD, ConceptType.F64)
        );
        final ConceptExpr fudge = assign(
            locals,
            stmts,
            "fudge",
            ConceptType.F64,
            mulF64(floorF64(addF64(divF64(fudgeLimit, fudgeYScale), f64(1.0E-7F))), fudgeYScale)
        );
        final ConceptExpr ry = assign(locals, stmts, "ry", ConceptType.F32, castF32(subF64(ryD, fudge)));

        final ConceptExpr x0 = assign(locals, stmts, "x0", ConceptType.I32, loadPAdj(permOff, fx));
        final ConceptExpr x1 = assign(locals, stmts, "x1", ConceptType.I32, ushrI32(x0, 8));

        final ConceptExpr xy00 = assign(locals, stmts, "xy00", ConceptType.I32, loadPAdj(permOff, addI32(x0, fy)));
        final ConceptExpr xy01 = assign(locals, stmts, "xy01", ConceptType.I32, ushrI32(xy00, 8));
        final ConceptExpr z000 = assign(locals, stmts, "z000", ConceptType.I32, loadPAdj(permOff, addI32(xy00, fz)));
        final ConceptExpr z001 = assign(locals, stmts, "z001", ConceptType.I32, loadPAdj(permOff, addI32(xy01, fz)));

        final ConceptExpr rx1 = assign(locals, stmts, "rx1", ConceptType.F32, subF32(rx, f32(1.0f)));
        final ConceptExpr ry1 = assign(locals, stmts, "ry1", ConceptType.F32, subF32(ry, f32(1.0f)));
        final ConceptExpr rz1 = assign(locals, stmts, "rz1", ConceptType.F32, subF32(rz, f32(1.0f)));

        final ConceptExpr d000 = assign(locals, stmts, "d000", ConceptType.F32, gradDot(andI32(z000, 15), rx, ry, rz));
        final ConceptExpr d001 = assign(locals, stmts, "d001", ConceptType.F32, gradDot(andI32(ushrI32(z000, 8), 15), rx, ry, rz1));
        final ConceptExpr d010 = assign(locals, stmts, "d010", ConceptType.F32, gradDot(andI32(z001, 15), rx, ry1, rz));
        final ConceptExpr d011 = assign(locals, stmts, "d011", ConceptType.F32, gradDot(andI32(ushrI32(z001, 8), 15), rx, ry1, rz1));

        final ConceptExpr xy10 = assign(locals, stmts, "xy10", ConceptType.I32, loadPAdj(permOff, addI32(x1, fy)));
        final ConceptExpr xy11 = assign(locals, stmts, "xy11", ConceptType.I32, ushrI32(xy10, 8));
        final ConceptExpr z100 = assign(locals, stmts, "z100", ConceptType.I32, loadPAdj(permOff, addI32(xy10, fz)));
        final ConceptExpr z101 = assign(locals, stmts, "z101", ConceptType.I32, loadPAdj(permOff, addI32(xy11, fz)));

        final ConceptExpr d100 = assign(locals, stmts, "d100", ConceptType.F32, gradDot(andI32(z100, 15), rx1, ry, rz));
        final ConceptExpr d101 = assign(locals, stmts, "d101", ConceptType.F32, gradDot(andI32(ushrI32(z100, 8), 15), rx1, ry, rz1));
        final ConceptExpr d110 = assign(locals, stmts, "d110", ConceptType.F32, gradDot(andI32(z101, 15), rx1, ry1, rz));
        final ConceptExpr d111 = assign(locals, stmts, "d111", ConceptType.F32, gradDot(andI32(ushrI32(z101, 8), 15), rx1, ry1, rz1));

        final ConceptExpr u = assign(locals, stmts, "u", ConceptType.F32, smoothstep(rx));
        final ConceptExpr v = assign(locals, stmts, "v", ConceptType.F32, smoothstep(ryAlpha));
        final ConceptExpr w = assign(locals, stmts, "w", ConceptType.F32, smoothstep(rz));

        final ConceptExpr x00 = assign(locals, stmts, "x00", ConceptType.F32, lerpF32(u, d000, d100));
        final ConceptExpr x10 = assign(locals, stmts, "x10", ConceptType.F32, lerpF32(u, d010, d110));
        final ConceptExpr x01 = assign(locals, stmts, "x01", ConceptType.F32, lerpF32(u, d001, d101));
        final ConceptExpr x11 = assign(locals, stmts, "x11", ConceptType.F32, lerpF32(u, d011, d111));

        final ConceptExpr y0 = assign(locals, stmts, "y0", ConceptType.F32, lerpF32(v, x00, x10));
        final ConceptExpr y1 = assign(locals, stmts, "y1", ConceptType.F32, lerpF32(v, x01, x11));
        stmts.add(new ConceptStmt.Return(lerpF32(w, y0, y1)));

        return new ConceptFunction(
            OWNER,
            HELPER_SMEARED_PERLIN,
            HELPER_SMEARED_PERLIN_DESC,
            List.of(
                new ConceptParam("permOff", ConceptType.I32, 0),
                new ConceptParam("x", ConceptType.F64, 1),
                new ConceptParam("y", ConceptType.F64, 3),
                new ConceptParam("z", ConceptType.F64, 5),
                new ConceptParam("origY", ConceptType.F64, 7),
                new ConceptParam("fudgeYScale", ConceptType.F64, 9)
            ),
            ConceptType.F32,
            locals,
            stmts,
            true
        );
    }

    /**
     * Emits the single shared {@code gl_perlin_3d(int permOffset, double x, double y, double z) -> float}
     * IR helper matching MC {@code 26.3}'s {@link PerlinNoise#sampleAndLerp} 1:1.
     */
    private static ConceptFunction buildPerlin3dHelper() {
        final Map<String, ConceptType> locals = new LinkedHashMap<>();
        final List<ConceptStmt> stmts = new ArrayList<>();

        final ConceptExpr permOff = new ConceptExpr.Var("permOff", ConceptType.I32);
        final ConceptExpr x = new ConceptExpr.Var("x", ConceptType.F64);
        final ConceptExpr y = new ConceptExpr.Var("y", ConceptType.F64);
        final ConceptExpr z = new ConceptExpr.Var("z", ConceptType.F64);

        final ConceptExpr fxD = assign(locals, stmts, "fxD", ConceptType.F64, floorF64(x));
        final ConceptExpr fyD = assign(locals, stmts, "fyD", ConceptType.F64, floorF64(y));
        final ConceptExpr fzD = assign(locals, stmts, "fzD", ConceptType.F64, floorF64(z));

        final ConceptExpr fx = assign(locals, stmts, "fx", ConceptType.I32, castI32(fxD));
        final ConceptExpr fy = assign(locals, stmts, "fy", ConceptType.I32, castI32(fyD));
        final ConceptExpr fz = assign(locals, stmts, "fz", ConceptType.I32, castI32(fzD));

        final ConceptExpr rx = assign(locals, stmts, "rx", ConceptType.F32, castF32(subF64(x, fxD)));
        final ConceptExpr ry = assign(locals, stmts, "ry", ConceptType.F32, castF32(subF64(y, fyD)));
        final ConceptExpr rz = assign(locals, stmts, "rz", ConceptType.F32, castF32(subF64(z, fzD)));

        final ConceptExpr x0 = assign(locals, stmts, "x0", ConceptType.I32, loadPAdj(permOff, fx));
        final ConceptExpr x1 = assign(locals, stmts, "x1", ConceptType.I32, ushrI32(x0, 8));

        final ConceptExpr xy00 = assign(locals, stmts, "xy00", ConceptType.I32, loadPAdj(permOff, addI32(x0, fy)));
        final ConceptExpr xy01 = assign(locals, stmts, "xy01", ConceptType.I32, ushrI32(xy00, 8));
        final ConceptExpr z000 = assign(locals, stmts, "z000", ConceptType.I32, loadPAdj(permOff, addI32(xy00, fz)));
        final ConceptExpr z001 = assign(locals, stmts, "z001", ConceptType.I32, loadPAdj(permOff, addI32(xy01, fz)));

        final ConceptExpr rx1 = assign(locals, stmts, "rx1", ConceptType.F32, subF32(rx, f32(1.0f)));
        final ConceptExpr ry1 = assign(locals, stmts, "ry1", ConceptType.F32, subF32(ry, f32(1.0f)));
        final ConceptExpr rz1 = assign(locals, stmts, "rz1", ConceptType.F32, subF32(rz, f32(1.0f)));

        final ConceptExpr d000 = assign(locals, stmts, "d000", ConceptType.F32, gradDot(andI32(z000, 15), rx, ry, rz));
        final ConceptExpr d001 = assign(locals, stmts, "d001", ConceptType.F32, gradDot(andI32(ushrI32(z000, 8), 15), rx, ry, rz1));
        final ConceptExpr d010 = assign(locals, stmts, "d010", ConceptType.F32, gradDot(andI32(z001, 15), rx, ry1, rz));
        final ConceptExpr d011 = assign(locals, stmts, "d011", ConceptType.F32, gradDot(andI32(ushrI32(z001, 8), 15), rx, ry1, rz1));

        final ConceptExpr xy10 = assign(locals, stmts, "xy10", ConceptType.I32, loadPAdj(permOff, addI32(x1, fy)));
        final ConceptExpr xy11 = assign(locals, stmts, "xy11", ConceptType.I32, ushrI32(xy10, 8));
        final ConceptExpr z100 = assign(locals, stmts, "z100", ConceptType.I32, loadPAdj(permOff, addI32(xy10, fz)));
        final ConceptExpr z101 = assign(locals, stmts, "z101", ConceptType.I32, loadPAdj(permOff, addI32(xy11, fz)));

        final ConceptExpr d100 = assign(locals, stmts, "d100", ConceptType.F32, gradDot(andI32(z100, 15), rx1, ry, rz));
        final ConceptExpr d101 = assign(locals, stmts, "d101", ConceptType.F32, gradDot(andI32(ushrI32(z100, 8), 15), rx1, ry, rz1));
        final ConceptExpr d110 = assign(locals, stmts, "d110", ConceptType.F32, gradDot(andI32(z101, 15), rx1, ry1, rz));
        final ConceptExpr d111 = assign(locals, stmts, "d111", ConceptType.F32, gradDot(andI32(ushrI32(z101, 8), 15), rx1, ry1, rz1));

        final ConceptExpr u = assign(locals, stmts, "u", ConceptType.F32, smoothstep(rx));
        final ConceptExpr v = assign(locals, stmts, "v", ConceptType.F32, smoothstep(ry));
        final ConceptExpr w = assign(locals, stmts, "w", ConceptType.F32, smoothstep(rz));

        final ConceptExpr x00 = assign(locals, stmts, "x00", ConceptType.F32, lerpF32(u, d000, d100));
        final ConceptExpr x10 = assign(locals, stmts, "x10", ConceptType.F32, lerpF32(u, d010, d110));
        final ConceptExpr x01 = assign(locals, stmts, "x01", ConceptType.F32, lerpF32(u, d001, d101));
        final ConceptExpr x11 = assign(locals, stmts, "x11", ConceptType.F32, lerpF32(u, d011, d111));

        final ConceptExpr y0 = assign(locals, stmts, "y0", ConceptType.F32, lerpF32(v, x00, x10));
        final ConceptExpr y1 = assign(locals, stmts, "y1", ConceptType.F32, lerpF32(v, x01, x11));
        stmts.add(new ConceptStmt.Return(lerpF32(w, y0, y1)));

        return new ConceptFunction(
            OWNER,
            HELPER_PERLIN,
            HELPER_PERLIN_DESC,
            List.of(
                new ConceptParam("permOff", ConceptType.I32, 0),
                new ConceptParam("x", ConceptType.F64, 1),
                new ConceptParam("y", ConceptType.F64, 3),
                new ConceptParam("z", ConceptType.F64, 5)
            ),
            ConceptType.F32,
            locals,
            stmts,
            true
        );
    }

    /**
     * Exact branch-free Perlin 16-gradient dot product matching {@link GradientNoise#GRADIENT}.
     */
    private static ConceptExpr gradDot(final ConceptExpr h, final ConceptExpr x, final ConceptExpr y, final ConceptExpr z) {
        // Matches GradientNoise.GRADIENT[0..15] via a branchless 4-level selection tree:
        // 0:(1,1,0), 1:(-1,1,0), 2:(1,-1,0), 3:(-1,-1,0), 4:(1,0,1), 5:(-1,0,1), 6:(1,0,-1), 7:(-1,0,-1),
        // 8:(0,1,1), 9:(0,-1,1), 10:(0,1,-1), 11:(0,-1,-1), 12:(1,1,0), 13:(0,-1,1), 14:(-1,1,0), 15:(0,-1,-1)
        final ConceptExpr g0_3 = new ConceptExpr.Ternary(
            eqI32(andI32(h, 2), 0),
            new ConceptExpr.Ternary(eqI32(andI32(h, 1), 0), addF32(x, y), subF32(y, x), ConceptType.F32),
            new ConceptExpr.Ternary(eqI32(andI32(h, 1), 0), subF32(x, y), subF32(negF32(x), y), ConceptType.F32),
            ConceptType.F32
        );
        final ConceptExpr g4_7 = new ConceptExpr.Ternary(
            eqI32(andI32(h, 2), 0),
            new ConceptExpr.Ternary(eqI32(andI32(h, 1), 0), addF32(x, z), subF32(z, x), ConceptType.F32),
            new ConceptExpr.Ternary(eqI32(andI32(h, 1), 0), subF32(x, z), subF32(negF32(x), z), ConceptType.F32),
            ConceptType.F32
        );
        final ConceptExpr g8_11 = new ConceptExpr.Ternary(
            eqI32(andI32(h, 2), 0),
            new ConceptExpr.Ternary(eqI32(andI32(h, 1), 0), addF32(y, z), subF32(z, y), ConceptType.F32),
            new ConceptExpr.Ternary(eqI32(andI32(h, 1), 0), subF32(y, z), subF32(negF32(y), z), ConceptType.F32),
            ConceptType.F32
        );
        final ConceptExpr g12_15 = new ConceptExpr.Ternary(
            eqI32(andI32(h, 2), 0),
            new ConceptExpr.Ternary(eqI32(andI32(h, 1), 0), addF32(x, y), subF32(z, y), ConceptType.F32),
            new ConceptExpr.Ternary(eqI32(andI32(h, 1), 0), subF32(y, x), subF32(negF32(y), z), ConceptType.F32),
            ConceptType.F32
        );
        final ConceptExpr lo = new ConceptExpr.Ternary(ltI32(h, 4), g0_3, g4_7, ConceptType.F32);
        final ConceptExpr hi = new ConceptExpr.Ternary(ltI32(h, 12), g8_11, g12_15, ConceptType.F32);
        return new ConceptExpr.Ternary(ltI32(h, 8), lo, hi, ConceptType.F32);
    }

    private static ConceptExpr smoothstep(final ConceptExpr t) {
        // t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f)
        final ConceptExpr inner = addF32(mulF32(t, subF32(mulF32(t, f32(6.0f)), f32(15.0f))), f32(10.0f));
        return mulF32(mulF32(mulF32(t, t), t), inner);
    }

    private static ConceptExpr lerpF32(final ConceptExpr alpha, final ConceptExpr a, final ConceptExpr b) {
        return addF32(a, mulF32(alpha, subF32(b, a)));
    }

    private static ConceptExpr loadPAdj(final ConceptExpr permOff, final ConceptExpr idx) {
        return new ConceptExpr.LutLoad(LUT_PERLIN_PADJ, addI32(permOff, andI32(idx, 255)), ConceptType.I32);
    }

    private static ConceptExpr.Var assign(
        final Map<String, ConceptType> locals,
        final List<ConceptStmt> stmts,
        final String name,
        final ConceptType type,
        final ConceptExpr expr
    ) {
        locals.put(name, type);
        stmts.add(new ConceptStmt.Assign(name, type, expr));
        return new ConceptExpr.Var(name, type);
    }

    private static boolean isZeroConst(final DensityFunction fn) {
        return fn instanceof ConstantFunction(float v) && v == 0.0f;
    }

    private static ConceptExpr i32(final int v) {
        return new ConceptExpr.IntConst(v);
    }

    private static ConceptExpr f32(final float v) {
        return new ConceptExpr.FloatConst(v);
    }

    private static ConceptExpr f64(final double v) {
        return new ConceptExpr.DoubleConst(v);
    }

    private static ConceptExpr castF32(final ConceptExpr e) {
        return new ConceptExpr.Cast(ConceptType.F32, e);
    }

    private static ConceptExpr castF64(final ConceptExpr e) {
        return new ConceptExpr.Cast(ConceptType.F64, e);
    }

    private static ConceptExpr castI32(final ConceptExpr e) {
        return new ConceptExpr.Cast(ConceptType.I32, e);
    }

    private static ConceptExpr floorF64(final ConceptExpr e) {
        return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.FLOOR, List.of(e), ConceptType.F64);
    }

    private static ConceptExpr addI32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.ADD, a, b, ConceptType.I32);
    }

    private static ConceptExpr subI32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.SUB, a, b, ConceptType.I32);
    }

    private static ConceptExpr mulI32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.MUL, a, b, ConceptType.I32);
    }

    private static ConceptExpr divI32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.DIV, a, b, ConceptType.I32);
    }

    private static ConceptExpr andI32(final ConceptExpr a, final int mask) {
        return new ConceptExpr.BinaryOp(BinaryOperator.BIT_AND, a, i32(mask), ConceptType.I32);
    }

    private static ConceptExpr ushrI32(final ConceptExpr a, final int shift) {
        return new ConceptExpr.BinaryOp(BinaryOperator.USHR, a, i32(shift), ConceptType.I32);
    }

    private static ConceptExpr eqI32(final ConceptExpr a, final int b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.EQ, a, i32(b), ConceptType.BOOL);
    }

    private static ConceptExpr ltI32(final ConceptExpr a, final int b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.LT, a, i32(b), ConceptType.BOOL);
    }

    private static ConceptExpr imin(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.MIN, List.of(a, b), ConceptType.I32);
    }

    private static ConceptExpr imax(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.MAX, List.of(a, b), ConceptType.I32);
    }

    private static ConceptExpr addF32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.ADD, a, b, ConceptType.F32);
    }

    private static ConceptExpr subF32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.SUB, a, b, ConceptType.F32);
    }

    private static ConceptExpr mulF32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.MUL, a, b, ConceptType.F32);
    }

    private static ConceptExpr divF32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.DIV, a, b, ConceptType.F32);
    }

    private static ConceptExpr negF32(final ConceptExpr a) {
        return new ConceptExpr.UnaryOp(UnaryOperator.NEG, a, ConceptType.F32);
    }

    private static ConceptExpr fmin(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.MIN, List.of(a, b), ConceptType.F32);
    }

    private static ConceptExpr fmax(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.MathIntrinsic(MathIntrinsicKind.MAX, List.of(a, b), ConceptType.F32);
    }

    private static ConceptExpr eqF32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.EQ, a, b, ConceptType.BOOL);
    }

    private static ConceptExpr ltF32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.LT, a, b, ConceptType.BOOL);
    }

    private static ConceptExpr gtF32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.GT, a, b, ConceptType.BOOL);
    }

    private static ConceptExpr geF32(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.GE, a, b, ConceptType.BOOL);
    }

    private static ConceptExpr addF64(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.ADD, a, b, ConceptType.F64);
    }

    private static ConceptExpr subF64(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.SUB, a, b, ConceptType.F64);
    }

    private static ConceptExpr mulF64(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.MUL, a, b, ConceptType.F64);
    }

    private static ConceptExpr divF64(final ConceptExpr a, final ConceptExpr b) {
        return new ConceptExpr.BinaryOp(BinaryOperator.DIV, a, b, ConceptType.F64);
    }
}
