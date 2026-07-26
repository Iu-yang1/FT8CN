subroutine ft8_downsample(dd,newdat,f0,c1)

  use iso_c_binding, only: c_float, c_float_complex, c_loc, c_f_pointer

! Downconvert to complex data sampled at 200 Hz ==> 32 samples/symbol

  parameter (NMAX=15*12000,NSPS=1920)
  parameter (NFFT1=192000,NFFT2=3200)      !192000/60 = 3200
  
  logical newdat,first
  complex c1(0:NFFT2-1)
  complex shifted(0:NFFT2-1)
  complex(c_float_complex), pointer, save :: cx(:)
  real dd(NMAX),taper(0:100)
  real(c_float), target, save :: x(NFFT1+2)
  data first/.true./
  save first,taper

  if(first) then
! 以标准 C 指针关联表达实数原位 FFT 工作区，避免 EQUIVALENCE 在 Flang -O2 下被错误优化。
     call c_f_pointer(c_loc(x(1)),cx,(/NFFT1/2+1/))
     pi=4.0*atan(1.0)
     do i=0,100
       taper(i)=0.5*(1.0+cos(i*pi/100))
     enddo
     first=.false.
  endif
  if(newdat) then
! Data in dd have changed, recompute the long FFT
     x(1:NMAX)=dd
     x(NMAX+1:NFFT1+2)=0.                       !Zero-pad the x array
     call four2a(cx,NFFT1,1,-1,0)             !r2c FFT to freq domain
     newdat=.false.
  endif
  df=12000.0/NFFT1
  baud=12000.0/NSPS
  i0=nint(f0/df)
  ft=f0+8.5*baud
  it=min(nint(ft/df),NFFT1/2)
  fb=f0-1.5*baud
  ib=max(1,nint(fb/df))
  k=0
  c1=0.
  do i=ib,it
   c1(k)=cx(i+1)
   k=k+1
  enddo
  c1(0:100)=c1(0:100)*taper(100:0:-1)
  c1(k-1-100:k-1)=c1(k-1-100:k-1)*taper
! 显式循环规避 Flang -O2 对零下标复数数组 CSHIFT 的错误 lowering。
  ishift=modulo(i0-ib,NFFT2)
  do i=0,NFFT2-1
     shifted(i)=c1(modulo(i+ishift,NFFT2))
  enddo
  c1=shifted
  call four2a(c1,NFFT2,1,1,1)            !c2c FFT back to time domain
  fac=1.0/sqrt(float(NFFT1)*NFFT2)
  c1=fac*c1

  return
end subroutine ft8_downsample
