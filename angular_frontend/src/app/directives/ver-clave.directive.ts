import { Directive, ElementRef, HostListener, OnDestroy, OnInit } from '@angular/core';

const OJO_ABIERTO =
  '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
  'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
  '<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';

const OJO_TACHADO =
  '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
  'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
  '<path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/>' +
  '<path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>' +
  '<line x1="1" y1="1" x2="23" y2="23"/></svg>';

/**
 * Botón "ojito" para mostrar/ocultar una contraseña. El navegador solo muestra
 * su propio ojito mientras el campo tiene foco; este se queda visible cuando el
 * campo pierde el foco (y tiene texto), y también cuando la clave ya está a la
 * vista, para poder volver a ocultarla.
 *
 * Uso:  <input type="password" appVerClave ...>
 */
@Directive({ selector: 'input[appVerClave]' })
export class VerClaveDirective implements OnInit, OnDestroy {
  private boton: HTMLButtonElement | null = null;

  constructor(private el: ElementRef<HTMLInputElement>) {}

  ngOnInit(): void {
    const host = this.el.nativeElement;
    const padre = host.parentElement;
    if (!padre) return;
    if (getComputedStyle(padre).position === 'static') padre.style.position = 'relative';

    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'ver-clave';
    b.tabIndex = -1;
    b.addEventListener('mousedown', (ev) => ev.preventDefault()); // no robar el foco
    b.addEventListener('click', (ev) => { ev.preventDefault(); this.alternar(); });
    padre.appendChild(b);
    this.boton = b;
    host.style.paddingRight = '36px';
    this.actualizar();
  }

  @HostListener('input')
  @HostListener('change')
  @HostListener('focus')
  @HostListener('blur')
  actualizar(): void {
    if (!this.boton) return;
    const host = this.el.nativeElement;
    const enTexto = host.type === 'text';
    const tiene = host.value.length > 0;
    const enfocado = document.activeElement === host;
    const mostrar = enTexto || (tiene && !enfocado);
    this.boton.style.display = mostrar ? 'flex' : 'none';
    this.boton.innerHTML = enTexto ? OJO_TACHADO : OJO_ABIERTO;
    const etiqueta = enTexto ? 'Ocultar la contraseña' : 'Mostrar la contraseña';
    this.boton.setAttribute('aria-label', etiqueta);
    this.boton.title = etiqueta;
    if (mostrar) this.colocar();
  }

  private alternar(): void {
    const host = this.el.nativeElement;
    host.type = host.type === 'text' ? 'password' : 'text';
    this.actualizar();
  }

  private colocar(): void {
    if (!this.boton) return;
    const padre = this.boton.parentElement;
    if (!padre) return;
    const pr = padre.getBoundingClientRect();
    const ir = this.el.nativeElement.getBoundingClientRect();
    const br = this.boton.getBoundingClientRect();
    this.boton.style.top = `${Math.round(ir.top - pr.top + (ir.height - br.height) / 2)}px`;
    this.boton.style.left = `${Math.round(ir.right - pr.left - br.width - 8)}px`;
  }

  ngOnDestroy(): void {
    this.boton?.remove();
    this.boton = null;
  }
}
