package lss.dev;

import java.util.*;
import java.util.logging.*;
import edu.mines.jtk.dsp.*;
import edu.mines.jtk.mosaic.*;
import edu.mines.jtk.opt.*;
import edu.mines.jtk.util.*;
import static edu.mines.jtk.util.ArrayMath.*;

import dnp.*;

/**
 * Forward, backward, and adjoint 1D wave operator.
 * <p>
 * The forward operator models data from a reflectivity model, while 
 * the backward and adjoint operators migrate data to produce a
 * reflectivity image.
 * <p>
 * Also included are forward, backward, and adjoint 1D wavefield
 * extrapolation operators.
 * <p>
 * @author Simon Luo
 * @version 2013.08.30
 */
public class WaveOperator {

  public WaveOperator(Sampling sz, Sampling st, float[] s) {
    _sz = sz;
    _st = st;
    _nz = sz.getCount();
    _nt = st.getCount();
    _dz = sz.getDelta();
    _dt = st.getDelta();
    _a = div((float)(_dt*_dt/(_dz*_dz)),mul(s,s));
  }

  /**
   * Applies the forward operator (modeling).
   */
  public float[] applyForward(float[] m) {
    float[][] u = new float[_nt][_nz];
    return applyForward(m,u);
  }
  public float[] applyForward(float[] m, float[][] u) {

    // (1) Expand to dimensions of wavefield.
    for (int it=0; it<_nt; ++it)
      copy(m,u[it]);

    // (2) Multiply by background wavefield.
    mul(getBackgroundWavefield(),u,u);

    // (3) Forward wavefield extrapolation.
    extrapolateForwardWavefield(u);

    // (4) Extract data at z=0.
    float[] d = new float[_nt]; 
    for (int it=0; it<_nt; ++it)
      d[it] = u[it][0];

    //pixels(getBackgroundWavefield(),_sz,_st,"incident wavefield");
    //pixels(u,_sz,_st,"scattered wavefield");
    //points(d,_st,"Time (s)","data");
    return d;
  }

  /**
   * Applies the adjoint operator (migration).
   */
  public float[] applyAdjoint(float[] d) {
    float[][] u = new float[_nt][_nz];
    return applyAdjoint(d,u);
  }
  public float[] applyAdjoint(float[] d, float[][] u) {

    // (4') Insert data at z=0.
    for (int it=0; it<_nt; ++it)
      u[it][0] = d[it];

    // (3') Adjoint wavefield extrapolation.
    extrapolateAdjointWavefield(u);
    //pixels(u,_sz,_st,"adjoint wavefield");

    // (2') Multiply by background wavefield.
    mul(getBackgroundWavefield(),u,u);

    // (1') Collapse to dimensions of model (imaging condition).
    float[] m = new float[_nz];
    for (int it=0; it<_nt; ++it)
      add(u[it],m,m);
    //points(m,_sz,"Depth (km)", "gradient");

    return m;
  }

  /**
   * Applies the backward operator (migration).
   */
  public float[] applyBackward(float[] d) {

    // (4') Insert data at z=0.
    float[][] u = new float[_nt][_nz];
    for (int it=0; it<_nt; ++it)
      u[it][0] = d[it];

    // (3') Backward wavefield extrapolation.
    extrapolateBackwardWavefield(u);

    // (2') Multiply by background wavefield.
    mul(getBackgroundWavefield(),u,u);

    // (1') Collapse wavefield to dimensions of model (imaging condition).
    float[] m = new float[_nz];
    for (int it=0; it<_nt; ++it)
      add(u[it],m,m);

    //pixels(u,_sz,_st,"backward wavefield");
    //points(m,_sz,"Depth (km)", "gradient");
    return m;
  }

  /**
   * Extrapolates forward wavefield.
   */
  public void extrapolateForwardWavefield(float[][] u) {
    int nz = _nz;
    int nt = _nt;
    float[] a = _a;
    float[] um,ui,up;
    for (int it=0; it<nt; ++it) {
      um = (it>0)?u[it-1]:new float[nz];
      ui = u[it];
      up = (it<nt-1)?u[it+1]:new float[nz];
      for (int iz=1; iz<nz-1; ++iz)
        up[iz] += a[iz]*(ui[iz+1]+ui[iz-1])+2.0f*(1.0f-a[iz])*ui[iz]-um[iz];
      up[0   ] += a[0   ]*ui[1   ]+2.0f*(1.0f-a[0   ])*ui[0   ]-um[0   ];
      up[nz-1] += a[nz-1]*ui[nz-2]+2.0f*(1.0f-a[nz-1])*ui[nz-1]-um[nz-1];
    }
  }

  /**
   * Extrapolates adjoint wavefield.
   */
  public void extrapolateAdjointWavefield(float[][] u) {
    int nz = _nz;
    int nt = _nt;
    float[] a = _a;
    float[] um,ui,up;
    float[] ut = new float[nz];
    for (int it=nt-1; it>=0; --it) {
      up = (it<nt-1)?u[it+1]:new float[nz];
      ui = u[it];
      um = (it>0)?u[it-1]:new float[nz];

      /*
      for (int iz=1; iz<nz-1; ++iz)
        um[iz] += a[iz+1]*ui[iz+1]+a[iz-1]*ui[iz-1]+
          2.0f*(1.0f-a[iz])*ui[iz]-up[iz];
      um[0   ] += a[1   ]*ui[1   ]+2.0f*(1.0f-a[0   ])*ui[0   ]-up[0   ];
      um[nz-1] += a[nz-2]*ui[nz-2]+2.0f*(1.0f-a[nz-1])*ui[nz-1]-up[nz-1];
      */
      um[0] -= up[0];
      ui[0] += 2.0f*(1.0f-a[0])*up[0];
      ui[1] += a[0]*up[0];
      um[nz-1] -= up[nz-1];
      ui[nz-2] += a[nz-1]*up[nz-1];
      ui[nz-1] += 2.0f*(1.0f-a[nz-1])*up[nz-1];
      for (int iz=1; iz<nz-1; ++iz) {
        um[iz  ] -= up[iz];
        ui[iz-1] += a[iz]*up[iz];
        ui[iz+1] += a[iz]*up[iz];
        ui[iz  ] += 2.0f*(1.0f-a[iz])*up[iz];
      }
    }
  }

  /**
   * Extrapolates backward wavefield.
   */
  public void extrapolateBackwardWavefield(float[][] u) {
    int nz = _nz;
    int nt = _nt;
    float[] a = _a;
    float[] um,ui,up;
    float[] ut = new float[nz];
    for (int it=nt-1; it>=0; --it) {
      up = (it<nt-1)?u[it+1]:new float[nz];
      ui = u[it];
      um = (it>0)?u[it-1]:new float[nz];
      for (int iz=1; iz<nz-1; ++iz)
        um[iz] += a[iz]*(ui[iz+1]-2.0f*ui[iz]+ui[iz-1])-up[iz]+2.0f*ui[iz];
      um[0   ] += a[0   ]*(ui[1   ]-2.0f*ui[0   ])-up[0   ]+2.0f*ui[0   ];
      um[nz-1] += a[nz-1]*(ui[nz-2]-2.0f*ui[nz-1])-up[nz-1]+2.0f*ui[nz-1];
    }
  }

  //////////////////////////////////////////////////////////////////////////
  // private

  private static final double FPEAK = 10.0; // Ricker peak frequency
  private float[][] _b = null; // background wavefield
  private float[] _a;
  private int _nz;
  private int _nt;
  private double _dz;
  private double _dt;
  private Sampling _sz;
  private Sampling _st;

  // Computes wavefield for background model.
  protected float[][] getBackgroundWavefield() {
    if (_b==null) {
      _b = new float[_nt][_nz];
      for (int it=0; it<_nt; ++it) {
        double t = _dt*it;
        _b[it][0] = ricker(t);
      }
      extrapolateForwardWavefield(_b);
      mul(-1.0f,_b,_b);
      //new RecursiveGaussianFilter(1.0).applyX2(_b,_b);

      // Fake absorbing boundary. XXX
      //absorb(_b);
    }
    return _b;
  }

  // Fake absorbing boundary.
  private void absorb(float[][] b) {
    int kmute = 0;
    float thresh = 0.1f*max(abs(b[0]));
    while (b[kmute][_nz-1]<thresh)
      ++kmute;
    for (int it=kmute; it<_nt; ++it)
      zero(b[it]);
  } 

  private static float ricker(double t) {
    double tdelay = 1.0/FPEAK;
    double x = PI*FPEAK*(t-tdelay);
    double xx = x*x;
    return (float)((1.0-2.0*xx)*exp(-xx));
  }

  //////////////////////////////////////////////////////////////////////////
  // testing

  // Tests:
  //   nt=1667 , constant slowness,  USE_ADJOINT=true,   niter=1
  //   nt=1667 , constant slowness,  USE_ADJOINT=true,  *niter=100*
  //   nt=1667 , constant slowness, *USE_ADJOINT=false*, niter=100
  //   nt=1667 , *ramp slowness*,    USE_ADJOINT=false,  niter=100
  //  (nt=1667 ,  ramp slowness,     USE_ADJOINT=false, damp=1.0e-5f)
  //   nt=1667 ,  ramp slowness,    *USE_ADJOINT=true*,  niter=100
  //  *nt=2801*, constant slowness,  USE_ADJOINT=true,  *niter=1*
  //   nt=2801 , constant slowness,  USE_ADJOINT=true,  *niter=100*

  private static final boolean USE_ADJOINT = true;

  public static void main(String[] args) { 
    //goMigrationQs(); // QuadraticSolver
    goMigrationCg(); // CgSolver
    //adjointTest();
    //adjointExtrapolateTest();
  }

  private static Par getPar() {
    Sampling sz = new Sampling(421,0.0120,0.0);
    Sampling st = new Sampling(1667,0.0015,0.0);
    //Sampling st = new Sampling(2801,0.0015,0.0);
    return new Par(sz,st);
  }
  private static float[] getSlowness(int nz) {
    return getConstantSlowness(0.25f,nz);
    //return getRandomSlowness(0.25f,nz);
    //return getRampSlowness(0.25f,0.30f,nz);
  }
  private static float[] getReflectivity(int nz) {
    return getLayeredReflectivity(nz);
    //return getRandomReflectivity(nz);
  }

  private static void goMigrationCg() {
    Par par = getPar();
    float[] s = par.s;
    float[] r = par.r;
    WaveOperator wave = par.wave;
    Sampling sz = par.sz;
    Sampling st = par.st;
    int nz = sz.getCount();
    int nt = st.getCount();
    double dz = sz.getDelta();
    double dt = st.getDelta();

    // RHS vector.
    float[][] u = new float[nt][nz];
    float[] d = wave.applyForward(r,u);
    float[] b = wave.applyAdjoint(d);
    VecArrayFloat1 vb = new VecArrayFloat1(b);

    float[][] q = copy(wave.getBackgroundWavefield());
    pixels(q,sz,st,"incident wavefield");
    for (int it=0; it<nt; ++it)
      copy(r,q[it]);
    pixels(q,sz,st,"reflectivity");
    pixels(u,sz,st,"scattered wavefield");

    // Model vector; solution vector.
    float[] x = new float[nz];
    VecArrayFloat1 vx = new VecArrayFloat1(x);

    // Conjugate gradient solver.
    int niter = 1;
    float damp = 0.0e-5f; // model damping
    A ma = new A(USE_ADJOINT,wave,damp*sum(mul(b,b))/b.length);
    CgSolver cg = new CgSolver(0.0001f,niter);
    cg.solve(ma,vb,vx);
    
    plots(d,wave.applyForward(x),s,r,x);
  }

  private static class A implements CgSolver.A {
    A(boolean useAdjoint, WaveOperator wave, float damp) {
      _adjoint = useAdjoint;
      _wave = wave;
      _damp = damp;
    }
    public void apply(Vec vx, Vec vy) {
      VecArrayFloat1 v1x = (VecArrayFloat1)vx;
      VecArrayFloat1 v1y = (VecArrayFloat1)vy;
      float[] x = v1x.getArray();
      float[] y = v1y.getArray();
      if (_adjoint) {
        copy(_wave.applyAdjoint(_wave.applyForward(x)),y);
      } else {
        copy(_wave.applyBackward(_wave.applyForward(x)),y);
      }
      if (_damp>0.0f)
        add(mul(_damp,x),y,y);
    }
    private WaveOperator _wave;
    private boolean _adjoint;
    private float _damp;
  }

  private static void goMigrationQs() {
    Par par = getPar();
    float[] s = par.s;
    float[] r = par.r;
    WaveOperator wave = par.wave;
    Sampling sz = par.sz;
    Sampling st = par.st;
    int nz = sz.getCount();
    int nt = st.getCount();
    double dz = sz.getDelta();
    double dt = st.getDelta();

    // Data vector.
    float[] d = wave.applyForward(r);
    ArrayVect1f vd = new ArrayVect1f(d,0,1.0);

    // Reference model vector (set to 0)
    float[] m = new float[nz];
    ArrayVect1f vm = new ArrayVect1f(m,0,1.0);

    // Progress monitoring.
    Logger log = Logger.getLogger("edu.mines.jtk.opt");
    LogMonitor monitor = new LogMonitor("QuadraticSolver",log);

    // Linear transform.
    WaveTransform ma = new WaveTransform(USE_ADJOINT,wave);

    // LinearTransform tests.
    System.out.println("Transpose precision: "+
      new TransformQuadratic(vd,vm,null,new LinearTransformWrapper(ma),true).
        getTransposePrecision()
    );

    // Quadratic solver.
    int niter = 100;
    boolean dampOnlyPerturbation = true;
    ArrayVect1f vx = (ArrayVect1f)QuadraticSolver.
      solve(vd,vm,ma,dampOnlyPerturbation,niter,monitor);
    float[] x = vx.getData();

    plots(d,wave.applyForward(x),s,r,x);
  }

  private static class WaveTransform implements LinearTransform {
    WaveTransform(boolean useAdjoint, WaveOperator wave) {
      _adjoint = useAdjoint;
      _wave = wave;
    }
    public void forward(Vect data, VectConst model) {
      ArrayVect1f d1f = (ArrayVect1f)data;
      ArrayVect1f m1f = (ArrayVect1f)model;
      float[] d = d1f.getData();
      float[] m = m1f.getData();
      copy(_wave.applyForward(m),d);
    }
    public void addTranspose(VectConst data, Vect model) {
      ArrayVect1f d1f = (ArrayVect1f)data;
      ArrayVect1f m1f = (ArrayVect1f)model;
      float[] d = d1f.getData();
      float[] m = m1f.getData();
      if (_adjoint) {
        add(_wave.applyAdjoint(d),m,m);
      } else {
        add(_wave.applyBackward(d),m,m);
      }
    }
    public void inverseHessian(Vect model) {
    }
    public void adjustRobustErrors(Vect dataError) {
    }
    private WaveOperator _wave;
    private boolean _adjoint;
  }

  private static class Par {
    public Par(Sampling sz, Sampling st) {
      this.sz = sz;
      this.st = st;
      this.s = getSlowness(sz.getCount());
      this.r = getReflectivity(sz.getCount());
      this.wave = new WaveOperator(sz,st,this.s);
    }
    public Sampling sz;
    public Sampling st;
    public float[] s; // slowness
    public float[] r; // reflectivity
    public WaveOperator wave;
  }

  private static float[] getConstantSlowness(float ss, int nz) {
    return fillfloat(ss,nz);
  }
  private static float[] getRampSlowness(float sshallow, float sdeep, int nz) {
    return rampfloat(sshallow,(sdeep-sshallow)/nz,nz);
  }
  private static float[] getRandomSlowness(float ss, int nz) {
    float scale = 0.5f; // scale of perturbation
    float[] s = mul(sub(randfloat(new Random(0123),nz),0.5f),scale);
    new RecursiveGaussianFilter(nz/16.0).apply0(s,s);
    add(ss,s,s);
    return s;
  }

  private static float[] getLayeredReflectivity(int nz) {
    float[] m = new float[nz];
    m[nz/4] = -1.0f;
    //m[nz/2] = -1.0f;
    //m[3*nz/4] = -1.0f;
    new RecursiveGaussianFilter(4.0).apply2(m,m);
    mul(1.0f/max(abs(m)),m,m);
    return m;
  }
  private static float[] getRandomReflectivity(int nz) {
    float[] m = sub(randfloat(new Random(0123),nz),0.5f);
    new RecursiveGaussianFilter(4.0).apply2(m,m);
    float[] t = new float[nz];
    t[nz/2] = 1.0f;
    new RecursiveGaussianFilter(nz/8.0).apply0(t,t);
    //mul(t,m,m);
    //points(t,new Sampling(t.length),"Depth (km)","taper");
    mul(1.0f/max(abs(m)),m,m);
    return m;
  }

  // Adjoint test for modeling/migration.
  private static void adjointTest() {
    Sampling sz = new Sampling(11,0.0120,0.0);
    Sampling st = new Sampling(11,0.0015,0.0);
    int nz = sz.getCount();
    int nt = st.getCount();
    double dz = sz.getDelta();
    double dt = st.getDelta();
    float[] s = getRampSlowness(0.25f,0.15f,nz);
    WaveOperator wave = new WaveOperator(sz,st,s);
    Random random = new Random();
    float[] m = sub(randfloat(random,nz),0.5f);
    float[] u = sub(randfloat(random,nt),0.5f);
    float sum1 = dot(wave.applyForward(m),u);
    float sum2 = dot(wave.applyAdjoint(u),m);
    System.out.println("adjoint test: "+compareDigits(sum1,sum2)+" digits");
    System.out.println(sum1);
    System.out.println(sum2);
  }

  // Adjoint test for forward/backward wavefield extrapolation.
  private static void adjointExtrapolateTest() {
    Sampling sz = new Sampling(11,0.0120,0.0);
    Sampling st = new Sampling(11,0.0015,0.0);
    int nz = sz.getCount();
    int nt = st.getCount();
    double dz = sz.getDelta();
    double dt = st.getDelta();
    float[] s = getRampSlowness(0.25f,0.15f,nz);
    WaveOperator wave = new WaveOperator(sz,st,s);
    Random random = new Random();
    float[][] ua = sub(randfloat(random,nz,nt),0.5f);
    float[][] ub = sub(randfloat(random,nz,nt),0.5f);
    float[][] va = copy(ua);
    float[][] vb = copy(ub);
    wave.extrapolateForwardWavefield(ua);
    wave.extrapolateAdjointWavefield(ub);
    float sum1 = dot(ua,vb);
    float sum2 = dot(ub,va);
    System.out.println("adjoint extrapolate test: "
      +compareDigits(sum1,sum2)+" digits");
    System.out.println(sum1);
    System.out.println(sum2);
  }

  // count number of same digits
  private static int compareDigits(float xa, float xb) {
    int digits; // significant digits
    boolean equal = false;
    for (digits=10; digits>0 && !equal; --digits)
      equal = new Almost(digits).equal(xa,xb);
    return digits+1;
  }

  private static float dot(float[] x, float[] y) {
    int n = x.length;
    float sum = 0.0f;
    for (int i=0; i<n; ++i)
      sum += x[i]*y[i];
    return sum;
  }
  private static float dot(float[][] x, float[][] y) {
    int n1 = x[0].length;
    int n2 = x.length;
    float sum = 0.0f;
    for (int i2=0; i2<n2; ++i2)
      for (int i1=0; i1<n1; ++i1)
        sum += x[i2][i1]*y[i2][i1];
    return sum;
  }

  private static void plots(
    float[] d, float[] p, float[] s, float[] r, float[] x) {
    Par par = getPar();
    Sampling sz = par.sz;
    Sampling st = par.st;
    points(s,sz,"Depth (km)", "background slowness");
    points(r,sz,"Depth (km)", "true reflectivity",-1.0,1.0);
    points(x,sz,"Depth (km)", "computed reflectivity",-1.0,1.0);
    points(d,st,"Time (s)","observed data",min(d),max(d));
    points(p,st,"Time (s)","predicted data",min(d),max(d));
  }
  private static void points(
    float[] x, Sampling s, String label, String title) {
    points(x,s,label,title,0.0,0.0);
  }
  private static void points(
    float[] x, Sampling s, String label,
    String title, double vmin, double vmax) {
    SimplePlot sp = SimplePlot.asPoints(s,x);
    sp.setTitle(title);
    sp.setHLabel(label);
    if (vmin<vmax)
      sp.setVLimits(vmin,vmax);
    sp.setSize(1010,740);
  }
  private static void pixels(
    float[][] x, Sampling s1, Sampling s2, String title) {
    SimplePlot sp = SimplePlot.asPixels(s1,s2,x);
    sp.setTitle(title);
    sp.setSize(1010,740);
    sp.setVLabel("Depth (km)");
    sp.setHLabel("Time (s)");
  }

}
