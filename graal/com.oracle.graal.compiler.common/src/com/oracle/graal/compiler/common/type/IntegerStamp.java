/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.graal.compiler.common.type;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.spi.*;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.BinaryOp;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.UnaryOp;

/**
 * Describes the possible values of a node that produces an int or long result.
 *
 * The description consists of (inclusive) lower and upper bounds and up (may be set) and down
 * (always set) bit-masks.
 */
public class IntegerStamp extends PrimitiveStamp {

    private final long lowerBound;
    private final long upperBound;
    private final long downMask;
    private final long upMask;

    public IntegerStamp(int bits, long lowerBound, long upperBound, long downMask, long upMask) {
        super(bits, OPS);
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.downMask = downMask;
        this.upMask = upMask;
        assert lowerBound >= CodeUtil.minValue(bits) : this;
        assert upperBound <= CodeUtil.maxValue(bits) : this;
        assert (downMask & CodeUtil.mask(bits)) == downMask : this;
        assert (upMask & CodeUtil.mask(bits)) == upMask : this;
    }

    @Override
    public IntegerStamp unrestricted() {
        return new IntegerStamp(getBits(), CodeUtil.minValue(getBits()), CodeUtil.maxValue(getBits()), 0, CodeUtil.mask(getBits()));
    }

    @Override
    public Stamp illegal() {
        return new IntegerStamp(getBits(), CodeUtil.maxValue(getBits()), CodeUtil.minValue(getBits()), CodeUtil.mask(getBits()), 0);
    }

    @Override
    public Stamp constant(Constant c, MetaAccessProvider meta) {
        long value = c.asLong();
        return StampFactory.forInteger(getBits(), value, value);
    }

    @Override
    public boolean isLegal() {
        return lowerBound <= upperBound;
    }

    @Override
    public Kind getStackKind() {
        if (getBits() > 32) {
            return Kind.Long;
        } else {
            return Kind.Int;
        }
    }

    @Override
    public LIRKind getLIRKind(LIRKindTool tool) {
        return tool.getIntegerKind(getBits());
    }

    @Override
    public ResolvedJavaType javaType(MetaAccessProvider metaAccess) {
        switch (getBits()) {
            case 1:
                return metaAccess.lookupJavaType(Boolean.TYPE);
            case 8:
                return metaAccess.lookupJavaType(Byte.TYPE);
            case 16:
                return metaAccess.lookupJavaType(Short.TYPE);
            case 32:
                return metaAccess.lookupJavaType(Integer.TYPE);
            case 64:
                return metaAccess.lookupJavaType(Long.TYPE);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    /**
     * The signed inclusive lower bound on the value described by this stamp.
     */
    public long lowerBound() {
        return lowerBound;
    }

    /**
     * The signed inclusive upper bound on the value described by this stamp.
     */
    public long upperBound() {
        return upperBound;
    }

    /**
     * This bit-mask describes the bits that are always set in the value described by this stamp.
     */
    public long downMask() {
        return downMask;
    }

    /**
     * This bit-mask describes the bits that can be set in the value described by this stamp.
     */
    public long upMask() {
        return upMask;
    }

    public boolean isUnrestricted() {
        return lowerBound == CodeUtil.minValue(getBits()) && upperBound == CodeUtil.maxValue(getBits()) && downMask == 0 && upMask == CodeUtil.mask(getBits());
    }

    public boolean contains(long value) {
        return value >= lowerBound && value <= upperBound && (value & downMask) == downMask && (value & upMask) == (value & CodeUtil.mask(getBits()));
    }

    public boolean isPositive() {
        return lowerBound() >= 0;
    }

    public boolean isNegative() {
        return upperBound() <= 0;
    }

    public boolean isStrictlyPositive() {
        return lowerBound() > 0;
    }

    public boolean isStrictlyNegative() {
        return upperBound() < 0;
    }

    public boolean canBePositive() {
        return upperBound() > 0;
    }

    public boolean canBeNegative() {
        return lowerBound() < 0;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append('i');
        str.append(getBits());
        if (lowerBound == upperBound) {
            str.append(" [").append(lowerBound).append(']');
        } else if (lowerBound != CodeUtil.minValue(getBits()) || upperBound != CodeUtil.maxValue(getBits())) {
            str.append(" [").append(lowerBound).append(" - ").append(upperBound).append(']');
        }
        if (downMask != 0) {
            str.append(" \u21ca");
            new Formatter(str).format("%016x", downMask);
        }
        if (upMask != CodeUtil.mask(getBits())) {
            str.append(" \u21c8");
            new Formatter(str).format("%016x", upMask);
        }
        return str.toString();
    }

    private Stamp createStamp(IntegerStamp other, long newUpperBound, long newLowerBound, long newDownMask, long newUpMask) {
        assert getBits() == other.getBits();
        if (newLowerBound > newUpperBound || (newDownMask & (~newUpMask)) != 0 || (newUpMask == 0 && (newLowerBound > 0 || newUpperBound < 0))) {
            return illegal();
        } else if (newLowerBound == lowerBound && newUpperBound == upperBound && newDownMask == downMask && newUpMask == upMask) {
            return this;
        } else if (newLowerBound == other.lowerBound && newUpperBound == other.upperBound && newDownMask == other.downMask && newUpMask == other.upMask) {
            return other;
        } else {
            return new IntegerStamp(getBits(), newLowerBound, newUpperBound, newDownMask, newUpMask);
        }
    }

    @Override
    public Stamp meet(Stamp otherStamp) {
        if (otherStamp == this) {
            return this;
        }
        if (!(otherStamp instanceof IntegerStamp)) {
            return StampFactory.illegal(Kind.Illegal);
        }
        if (equals(otherStamp)) {
            return this;
        }
        IntegerStamp other = (IntegerStamp) otherStamp;
        return createStamp(other, Math.max(upperBound, other.upperBound), Math.min(lowerBound, other.lowerBound), downMask & other.downMask, upMask | other.upMask);
    }

    @Override
    public Stamp join(Stamp otherStamp) {
        if (otherStamp == this) {
            return this;
        }
        if (!(otherStamp instanceof IntegerStamp)) {
            return StampFactory.illegal(Kind.Illegal);
        }
        IntegerStamp other = (IntegerStamp) otherStamp;
        long newDownMask = downMask | other.downMask;
        long newLowerBound = Math.max(lowerBound, other.lowerBound) | newDownMask;
        return createStamp(other, Math.min(upperBound, other.upperBound), newLowerBound, newDownMask, upMask & other.upMask);
    }

    @Override
    public boolean isCompatible(Stamp stamp) {
        if (this == stamp) {
            return true;
        }
        if (stamp instanceof IntegerStamp) {
            IntegerStamp other = (IntegerStamp) stamp;
            return getBits() == other.getBits();
        }
        return false;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + super.hashCode();
        result = prime * result + (int) (lowerBound ^ (lowerBound >>> 32));
        result = prime * result + (int) (upperBound ^ (upperBound >>> 32));
        result = prime * result + (int) (downMask ^ (downMask >>> 32));
        result = prime * result + (int) (upMask ^ (upMask >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass() || !super.equals(obj)) {
            return false;
        }
        IntegerStamp other = (IntegerStamp) obj;
        if (lowerBound != other.lowerBound || upperBound != other.upperBound || downMask != other.downMask || upMask != other.upMask) {
            return false;
        }
        return super.equals(other);
    }

    public static long upMaskFor(int bits, long lowerBound, long upperBound) {
        long mask = lowerBound | upperBound;
        if (mask == 0) {
            return 0;
        } else {
            return ((-1L) >>> Long.numberOfLeadingZeros(mask)) & CodeUtil.mask(bits);
        }
    }

    /**
     * Checks if the 2 stamps represent values of the same sign. Returns true if the two stamps are
     * both positive of null or if they are both strictly negative
     *
     * @return true if the two stamps are both positive of null or if they are both strictly
     *         negative
     */
    public static boolean sameSign(IntegerStamp s1, IntegerStamp s2) {
        return s1.isPositive() && s2.isPositive() || s1.isStrictlyNegative() && s2.isStrictlyNegative();
    }

    @Override
    public Constant asConstant() {
        if (lowerBound == upperBound) {
            switch (getBits()) {
                case 1:
                    return Constant.forBoolean(lowerBound != 0);
                case 8:
                    return Constant.forByte((byte) lowerBound);
                case 16:
                    return Constant.forShort((short) lowerBound);
                case 32:
                    return Constant.forInt((int) lowerBound);
                case 64:
                    return Constant.forLong(lowerBound);
            }
        }
        return null;
    }

    private static boolean addOverflowsPositively(long x, long y, int bits) {
        long result = x + y;
        if (bits == 64) {
            return (~x & ~y & result) < 0;
        } else {
            return result > CodeUtil.maxValue(bits);
        }
    }

    private static boolean addOverflowsNegatively(long x, long y, int bits) {
        long result = x + y;
        if (bits == 64) {
            return (x & y & ~result) < 0;
        } else {
            return result < CodeUtil.minValue(bits);
        }
    }

    private static long carryBits(long x, long y) {
        return (x + y) ^ x ^ y;
    }

    public static final ArithmeticOpTable OPS = new ArithmeticOpTable();

    static {
        OPS.neg = new UnaryOp() {

            @Override
            public Constant foldConstant(Constant value) {
                return Constant.forIntegerKind(value.getKind(), -value.asLong());
            }

            @Override
            public Stamp foldStamp(Stamp s) {
                IntegerStamp stamp = (IntegerStamp) s;
                int bits = stamp.getBits();
                if (stamp.lowerBound() != CodeUtil.minValue(bits)) {
                    // TODO(ls) check if the mask calculation is correct...
                    return StampFactory.forInteger(bits, -stamp.upperBound(), -stamp.lowerBound());
                } else {
                    return stamp.unrestricted();
                }
            }
        };

        OPS.add = new BinaryOp(true, true) {

            @Override
            public Constant foldConstant(Constant a, Constant b) {
                assert a.getKind() == b.getKind();
                return Constant.forIntegerKind(a.getKind(), a.asLong() + b.asLong());
            }

            @Override
            public Stamp foldStamp(Stamp stamp1, Stamp stamp2) {
                IntegerStamp a = (IntegerStamp) stamp1;
                IntegerStamp b = (IntegerStamp) stamp2;

                int bits = a.getBits();
                assert bits == b.getBits();

                if (a.isUnrestricted()) {
                    return a;
                } else if (b.isUnrestricted()) {
                    return b;
                }
                long defaultMask = CodeUtil.mask(bits);
                long variableBits = (a.downMask() ^ a.upMask()) | (b.downMask() ^ b.upMask());
                long variableBitsWithCarry = variableBits | (carryBits(a.downMask(), b.downMask()) ^ carryBits(a.upMask(), b.upMask()));
                long newDownMask = (a.downMask() + b.downMask()) & ~variableBitsWithCarry;
                long newUpMask = (a.downMask() + b.downMask()) | variableBitsWithCarry;

                newDownMask &= defaultMask;
                newUpMask &= defaultMask;

                long newLowerBound;
                long newUpperBound;
                boolean lowerOverflowsPositively = addOverflowsPositively(a.lowerBound(), b.lowerBound(), bits);
                boolean upperOverflowsPositively = addOverflowsPositively(a.upperBound(), b.upperBound(), bits);
                boolean lowerOverflowsNegatively = addOverflowsNegatively(a.lowerBound(), b.lowerBound(), bits);
                boolean upperOverflowsNegatively = addOverflowsNegatively(a.upperBound(), b.upperBound(), bits);
                if ((lowerOverflowsNegatively && !upperOverflowsNegatively) || (!lowerOverflowsPositively && upperOverflowsPositively)) {
                    newLowerBound = CodeUtil.minValue(bits);
                    newUpperBound = CodeUtil.maxValue(bits);
                } else {
                    newLowerBound = CodeUtil.signExtend((a.lowerBound() + b.lowerBound()) & defaultMask, bits);
                    newUpperBound = CodeUtil.signExtend((a.upperBound() + b.upperBound()) & defaultMask, bits);
                }
                IntegerStamp limit = StampFactory.forInteger(bits, newLowerBound, newUpperBound);
                newUpMask &= limit.upMask();
                newUpperBound = CodeUtil.signExtend(newUpperBound & newUpMask, bits);
                newDownMask |= limit.downMask();
                newLowerBound |= newDownMask;
                return new IntegerStamp(bits, newLowerBound, newUpperBound, newDownMask, newUpMask);
            }

            @Override
            public boolean isNeutral(Constant n) {
                return n.asLong() == 0;
            }
        };

        OPS.sub = new BinaryOp(true, false) {

            @Override
            public Constant foldConstant(Constant a, Constant b) {
                assert a.getKind() == b.getKind();
                return Constant.forIntegerKind(a.getKind(), a.asLong() - b.asLong());
            }

            @Override
            public Stamp foldStamp(Stamp a, Stamp b) {
                return OPS.add.foldStamp(a, OPS.neg.foldStamp(b));
            }

            @Override
            public boolean isNeutral(Constant n) {
                return n.asLong() == 0;
            }

            @Override
            public Constant getZero(Stamp s) {
                IntegerStamp stamp = (IntegerStamp) s;
                return Constant.forPrimitiveInt(stamp.getBits(), 0);
            }
        };

        OPS.mul = new BinaryOp(true, true) {

            @Override
            public Constant foldConstant(Constant a, Constant b) {
                assert a.getKind() == b.getKind();
                return Constant.forIntegerKind(a.getKind(), a.asLong() * b.asLong());
            }

            @Override
            public Stamp foldStamp(Stamp stamp1, Stamp stamp2) {
                IntegerStamp a = (IntegerStamp) stamp1;
                IntegerStamp b = (IntegerStamp) stamp2;
                if (a.upMask() == 0) {
                    return a;
                } else if (b.upMask() == 0) {
                    return b;
                } else {
                    // TODO
                    return a.unrestricted();
                }
            }

            @Override
            public boolean isNeutral(Constant n) {
                return n.asLong() == 1;
            }
        };

        OPS.div = new BinaryOp(true, false) {

            @Override
            public Constant foldConstant(Constant a, Constant b) {
                assert a.getKind() == b.getKind();
                return Constant.forIntegerKind(a.getKind(), a.asLong() / b.asLong());
            }

            @Override
            public Stamp foldStamp(Stamp stamp1, Stamp stamp2) {
                IntegerStamp a = (IntegerStamp) stamp1;
                IntegerStamp b = (IntegerStamp) stamp2;
                assert a.getBits() == b.getBits();
                if (b.isStrictlyPositive()) {
                    long newLowerBound = a.lowerBound() / b.lowerBound();
                    long newUpperBound = a.upperBound() / b.lowerBound();
                    return StampFactory.forInteger(a.getBits(), newLowerBound, newUpperBound);
                } else {
                    return a.unrestricted();
                }
            }

            @Override
            public boolean isNeutral(Constant n) {
                return n.asLong() == 1;
            }
        };

        OPS.rem = new BinaryOp(false, false) {

            @Override
            public Constant foldConstant(Constant a, Constant b) {
                assert a.getKind() == b.getKind();
                return Constant.forIntegerKind(a.getKind(), a.asLong() % b.asLong());
            }

            @Override
            public Stamp foldStamp(Stamp stamp1, Stamp stamp2) {
                IntegerStamp a = (IntegerStamp) stamp1;
                IntegerStamp b = (IntegerStamp) stamp2;
                assert a.getBits() == b.getBits();
                // zero is always possible
                long newLowerBound = Math.min(a.lowerBound(), 0);
                long newUpperBound = Math.max(a.upperBound(), 0);

                long magnitude; // the maximum absolute value of the result, derived from b
                if (b.lowerBound() == CodeUtil.minValue(b.getBits())) {
                    // Math.abs(...) - 1 does not work in a case
                    magnitude = CodeUtil.maxValue(b.getBits());
                } else {
                    magnitude = Math.max(Math.abs(b.lowerBound()), Math.abs(b.upperBound())) - 1;
                }
                newLowerBound = Math.max(newLowerBound, -magnitude);
                newUpperBound = Math.min(newUpperBound, magnitude);

                return StampFactory.forInteger(a.getBits(), newLowerBound, newUpperBound);
            }
        };
    }
}
