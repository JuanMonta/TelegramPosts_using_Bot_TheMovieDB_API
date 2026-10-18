import { Directive, ElementRef, HostListener, OnDestroy } from '@angular/core';

/**
 * Icono "i" con globo de ayuda. El texto se toma del hijo `.dt-info-globo`
 * (que queda oculto) y el globo visible se dibuja en un elemento pegado a
 * <body> (position: fixed), así NO lo recorta la ventana/modal que lo contiene
 * (que tiene scroll y overflow). Se coloca encima del icono y, si no cabe,
 * debajo; siempre dentro de la pantalla.
 *
 * Uso:  <span class="info-i" tabindex="0" appInfo>i<span class="dt-info-globo">texto</span></span>
 */
@Directive({ selector: '[appInfo]' })
export class InfoDirective implements OnDestroy {
  private globo: HTMLDivElement;
  private texto = '';

  constructor(private el: ElementRef<HTMLElement>) {
    this.globo = document.createElement('div');
    this.globo.className = 'info-globo';
    document.body.appendChild(this.globo);
  }

  @HostListener('mouseenter')
  @HostListener('focus')
  mostrar(): void {
    if (!this.texto) {
      const fuente = this.el.nativeElement.querySelector('.dt-info-globo');
      this.texto = fuente?.textContent?.trim() || '';
    }
    if (!this.texto) return;
    this.globo.textContent = this.texto;
    this.globo.classList.add('visible');
    this.colocar();
  }

  @HostListener('mouseleave')
  @HostListener('blur')
  ocultar(): void {
    this.globo.classList.remove('visible');
  }

  private colocar(): void {
    const r = this.el.nativeElement.getBoundingClientRect();
    const g = this.globo.getBoundingClientRect();
    const m = 8;
    let top = r.top - g.height - m;
    if (top < m) top = r.bottom + m;
    let left = r.left + r.width / 2 - g.width / 2;
    left = Math.max(m, Math.min(left, window.innerWidth - g.width - m));
    this.globo.style.top = `${Math.round(top)}px`;
    this.globo.style.left = `${Math.round(left)}px`;
  }

  ngOnDestroy(): void {
    this.globo.remove();
  }
}
